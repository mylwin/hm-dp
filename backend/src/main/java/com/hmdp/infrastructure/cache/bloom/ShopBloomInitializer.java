package com.hmdp.infrastructure.cache.bloom;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.ShopBloomProperties;
import com.hmdp.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.ShopConstants.SHOP_BLOOM_MBEAN_NAME;

/**
 * 启动时复用或构建 ID 快照，并提供本地 JMX 手动重建；通过生命周期写锁协调新增。
 *
 * @author myl
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ManagedResource(objectName = SHOP_BLOOM_MBEAN_NAME, description = "商铺布隆快照维护")
public class ShopBloomInitializer implements ApplicationRunner {
    private final ShopBloomProperties properties;
    private final ShopBloomFilter bloom;
    private final ShopMapper mapper;

    /**
     * 构建结果状态，BUSY 和 REUSED 不表示发起了一次新扫描。
     */
    private enum BuildStatus {NEVER, DISABLED, BUSY, REUSED, BUILDING, SUCCESS, FAILED, INTERRUPTED}

    /**
     * 已完成扫描的统计。
     *
     * @param loadedCount 实际导入的 ID 数量
     * @param upperBound  本次扫描固定的最大 ID
     */
    private record ScanResult(long loadedCount, long upperBound) {
    }

    /**
     * 启动时初始化布隆过滤器，已有可用快照时复用，不强制重建。
     *
     * @param args Spring Boot 启动参数，本方法不读取具体参数
     */
    @Override
    public void run(ApplicationArguments args) {
        build(false);
    }

    /**
     * 强制重建当前版本快照；仅在取得生命周期写锁后执行同步扫描。
     * 由 Spring JMX 开关控制导出，启用后由本机维护人员调用，结果不会自动启用拦截。
     *
     * @return JSON 结果，status 表示成功、失败、繁忙、中断或组件关闭；
     * loadedCount 和 upperBound 仅在扫描完成时提供
     */
    @ManagedOperation(description = "强制重建当前版本快照，返回 JSON 结果")
    public String rebuild() {
        return build(true);
    }

    /**
     * 获取当前版本最近一次实际构建的状态，忙碌调用不会覆盖正在进行的构建记录。
     *
     * @return 构建记录 JSON；没有记录时返回 status 为 NEVER 的 JSON
     */
    @ManagedAttribute(description = "最近一次实际构建状态，JSON 格式")
    public String getLastBuildStatus() {
        String status = bloom.lastBuildStatus();
        return status == null ? result(BuildStatus.NEVER, "尚无构建记录", null, null) : status;
    }

    /**
     * 在生命周期写锁内执行构建，按调用模式决定是否复用已有快照。
     *
     * @param force true 表示即使已有可用快照也重建，false 表示启动时优先复用
     * @return JSON 结果；异常在当前构建内记录，不影响应用启动或原有可用快照
     */
    private String build(boolean force) {
        if (!properties.isEnabled()) {
            log.debug("商铺布隆过滤器未启用，跳过快照构建");
            return result(BuildStatus.DISABLED, "布隆组件未启用", null, null);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return result(BuildStatus.FAILED, "快照构建必须在事务外调用", null, null);
        }
        RLock lock = null;
        boolean locked = false;
        boolean buildStarted = false;
        boolean preserveReady = false;
        RBloomFilter<Long> candidate = null;
        try {
            lock = bloom.initializationLock();
            // 多实例只允许一个构建；其他实例继续正常查询，后续读取共享就绪状态。
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("商铺布隆快照构建锁被其他实例持有，跳过快照构建");
                return result(BuildStatus.BUSY, "生命周期锁忙碌，请稍后手动重试", null, null);
            }
            preserveReady = bloom.hasReadySnapshot();
            if (preserveReady && !force) {
                log.debug("商铺布隆快照已就绪，跳过快照构建");
                return result(BuildStatus.REUSED, "复用已有就绪快照", null, null);
            }
            // 已有 READY 时保留它供查询使用，构建进度写入独立的构建状态键。
            if (!preserveReady) {
                // 标记布隆过滤器为构建中；从这一步起失败才允许覆盖状态为 FAILED。
                bloom.markBuilding();
            }
            buildStarted = true;
            recordBuildResult(result(BuildStatus.BUILDING, "正在构建候选快照", null, null));
            candidate = bloom.newCandidate();
            ScanResult scan = fillCandidate(candidate);
            String candidateName = candidate.getName();
            // 发布布隆过滤器快照：只有完整扫描成功后才替换查询使用的元数据。
            bloom.publishReady(candidate, scan.loadedCount(), scan.upperBound());
            // 已发布的快照不能作为失败候选清理，避免影响正常查询。
            candidate = null;
            preserveReady = true;
            String completed = result(BuildStatus.SUCCESS, "候选快照已完整发布", scan, candidateName);
            recordBuildResult(completed);
            return completed;
        } catch (InterruptedException e) {
            // InterruptedException 会清除中断标记，这里恢复标记，
            // 保留中断信息供上层感知；本次构建不重试。
            Thread.currentThread().interrupt();
            log.warn("商铺布隆快照构建等待被中断，继续使用原有缓存链路");
            return result(BuildStatus.INTERRUPTED, "等待构建锁被中断，本次不重试", null, null);
        } catch (RuntimeException e) {
            log.warn("商铺布隆快照构建失败，继续使用原有缓存链路或旧快照", e);
            // 只有真正开始构建且没有旧 READY 时，才允许将活动状态改为 FAILED。
            if (locked && buildStarted && !preserveReady) {
                markFailedSafely();
            }
            String failed = result(BuildStatus.FAILED, e.getMessage(), null, null);
            if (locked && buildStarted) {
                recordBuildResult(failed);
            }
            return failed;
        } finally {
            cleanupCandidate(candidate);
            releaseBuildLock(lock, locked);
        }
    }

    /**
     * 初始化独立候选过滤器，并在固定上界内分批导入商铺 ID。
     *
     * @param candidate 本次独占的未发布候选过滤器
     * @return 全部批次完成后的 ID 数量和扫描上界
     * @throws IllegalStateException 候选初始化失败、游标异常或导入数量超过预期容量
     */
    private ScanResult fillCandidate(RBloomFilter<Long> candidate) {
        // 尝试初始化布隆过滤器，独立候选已存在配置时不能继续构建。
        if (!candidate.tryInit(properties.getExpectedInsertions(), properties.getFalseProbability())) {
            throw new IllegalStateException("新的商铺布隆快照已被初始化");
        }
        log.debug("开始构建商铺布隆快照，版本{}，候选{}，预期容量{}，误判率{}，批量大小{}",
                properties.getVersion(), candidate.getName(), properties.getExpectedInsertions(),
                properties.getFalseProbability(), properties.getBatchSize());
        // 计算商铺ID的上界。
        long upperBound = mapper.selectMaxShopIdForBloom();
        log.debug("商铺布隆快照ID上界{}", upperBound);
        long cursor = 0;
        long count = 0;
        // 分批加载商铺ID并添加到布隆过滤器中。
        while (cursor < upperBound) {
            List<Long> ids = mapper.selectShopIdsForBloom(cursor, upperBound, properties.getBatchSize());
            if (ids.isEmpty()) {
                break;
            }
            long nextCursor = ids.get(ids.size() - 1);
            if (nextCursor <= cursor || nextCursor > upperBound) {
                throw new IllegalStateException("商铺布隆快照游标异常");
            }
            count += ids.size();
            if (count > properties.getExpectedInsertions()) {
                throw new IllegalStateException("商铺ID数量超过布隆过滤器预期容量，请提高容量并使用新版本");
            }
            candidate.add(ids);
            cursor = nextCursor;
            log.debug("商铺布隆快照加载批次完成，游标{}/{}，累计{}个ID", cursor, upperBound, count);
        }
        log.debug("商铺布隆快照构建完成，版本{}，加载{}个ID，上界{}", properties.getVersion(), count, upperBound);
        return new ScanResult(count, upperBound);
    }

    /**
     * 生成维护结果 JSON，不将未完成扫描的数量误写为零。
     *
     * @param status     本次调用或实际构建的结果状态
     * @param message    状态原因，异常没有消息时允许为 null
     * @param scan       已完成扫描的统计，未完成时为 null
     * @param filterName 已成功发布的候选名称，未发布时为 null
     * @return 包含版本、状态、时间、原因及可用统计的 JSON
     */
    private String result(BuildStatus status, String message, ScanResult scan, String filterName) {
        return JSONUtil.createObj().set("status", status.name()).set("version", properties.getVersion())
                .set("updatedAt", Instant.now().toString()).set("message", message)
                .set("loadedCount", scan == null ? null : scan.loadedCount())
                .set("upperBound", scan == null ? null : scan.upperBound()).set("filterName", filterName).toString();
    }

    /**
     * 记录构建结果，记录失败只写日志，不能将已经发布的快照标记为失败或清理。
     *
     * @param json 本次实际构建的 JSON 状态
     */
    private void recordBuildResult(String json) {
        try {
            bloom.recordBuildStatus(json);
        } catch (RuntimeException e) {
            log.warn("无法记录商铺布隆快照构建状态", e);
        }
    }

    /**
     * 将本次未产生可用快照的活动状态标记为失败，记录异常不覆盖原构建异常。
     */
    private void markFailedSafely() {
        try {
            bloom.markFailed();
        } catch (RuntimeException cleanupFailure) {
            log.warn("无法记录商铺布隆快照失败状态", cleanupFailure);
        }
    }

    /**
     * 清理尚未发布的候选快照，清理异常不影响后续解锁。
     *
     * @param candidate 本次未发布候选；为 null 表示没有候选或已经发布
     */
    private void cleanupCandidate(RBloomFilter<Long> candidate) {
        if (candidate != null) {
            try {
                candidate.delete();
            } catch (RuntimeException cleanupFailure) {
                log.warn("无法清理未完成的商铺布隆候选快照", cleanupFailure);
            }
        }
    }

    /**
     * 仅释放本次取得且当前线程仍持有的生命周期写锁。
     *
     * @param lock   本次使用的写锁，尚未取得锁对象时允许为 null
     * @param locked 是否已成功取得该锁
     */
    private void releaseBuildLock(RLock lock, boolean locked) {
        if (locked) {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException cleanupFailure) {
                log.warn("无法释放商铺布隆快照构建锁", cleanupFailure);
            }
        }
    }
}
