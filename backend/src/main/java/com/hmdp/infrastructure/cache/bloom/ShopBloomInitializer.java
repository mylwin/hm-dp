package com.hmdp.infrastructure.cache.bloom;

import com.hmdp.config.ShopBloomProperties;
import com.hmdp.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 启动时分批构建ID快照，与新增流程通过生命周期读写锁协调，不启用负向拦截。
 * @author myl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopBloomInitializer implements ApplicationRunner {
    private final ShopBloomProperties properties;
    private final ShopBloomFilter bloom;
    private final ShopMapper mapper;

    /**
     * 初始化布隆过滤器。
     */
    @Override
    public void run(ApplicationArguments args) {
        // 如果未启用布隆过滤器，则不进行任何操作
        if (!properties.isEnabled()) {
            log.debug("商铺布隆过滤器未启用，跳过快照构建");
            return;
        }
        // 尝试获取初始化锁，如果获取失败则表示有其他实例正在构建布隆过滤器，当前实例继续使用原有缓存链路
        RLock lock = null;
        boolean locked = false;
        boolean buildStarted = false;
        RBloomFilter<Long> candidate = null;
        try {
            lock = bloom.initializationLock();
            // 多实例只允许一个构建；其他实例继续正常查询，后续读取共享就绪状态。
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            // 如果获取锁失败或者已经存在就绪的快照，则当前实例继续使用原有缓存链路
            if (!locked) {
                log.debug("商铺布隆快照构建锁被其他实例持有，跳过快照构建");
                return;
            }
            if (bloom.hasReadySnapshot()) {
                log.debug("商铺布隆快照已就绪，跳过快照构建");
                return;
            }
            // 到这里说明没有已经就绪的快照，开始构建新的快照
            // 标记布隆过滤器为构建中；从这一步起失败才允许覆盖状态为 FAILED。
            bloom.markBuilding();
            buildStarted = true;
            candidate = bloom.newCandidate();
            // 尝试初始化布隆过滤器，如果失败则表示有其他实例已经初始化了布隆过滤器，当前实例继续使用原有缓存链路
            if (!candidate.tryInit(properties.getExpectedInsertions(), properties.getFalseProbability())) {
                throw new IllegalStateException("新的商铺布隆快照已被初始化");
            }
            log.debug("开始构建商铺布隆快照，版本{}，候选{}，预期容量{}，误判率{}，批量大小{}",
                    properties.getVersion(), candidate.getName(), properties.getExpectedInsertions(),
                    properties.getFalseProbability(), properties.getBatchSize());
            // 计算商铺ID的上界
            long upperBound = mapper.selectMaxShopIdForBloom();
            log.debug("商铺布隆快照ID上界{}", upperBound);
            long cursor = 0;
            long count = 0;
            // 分批加载商铺ID并添加到布隆过滤器中
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
            // 发布布隆过滤器快照
            bloom.publishReady(candidate, count, upperBound);
            log.debug("商铺布隆快照构建完成，版本{}，加载{}个ID，上界{}", properties.getVersion(), count, upperBound);
            // 已发布的快照不能作为失败候选清理，避免影响正常查询
            candidate = null;
        } catch (InterruptedException e) {
            // InterruptedException 会清除中断标记，这里恢复标记，
            // 保留中断信息供上层感知；本次构建不重试。
            Thread.currentThread().interrupt();
            log.warn("商铺布隆快照构建等待被中断，继续使用原有缓存链路");
        } catch (RuntimeException e) {
            log.warn("商铺布隆快照初始化失败，继续使用原有缓存链路", e);
            // 只有真正开始构建后才能把状态改为 FAILED，避免预检查异常覆盖其他实例的 READY 快照。
            if (locked && buildStarted) {
                try {
                    bloom.markFailed();
                } catch (RuntimeException cleanupFailure) {
                    log.warn("无法记录商铺布隆快照失败状态", cleanupFailure);
                }
            }
        } finally {
            // 如果 candidate 不为空，则尝试删除
            if (candidate != null) {
                try {
                    candidate.delete();
                } catch (RuntimeException cleanupFailure) {
                    log.warn("无法清理未完成的商铺布隆候选快照", cleanupFailure);
                }
            }
            // 如果获取了初始化锁，则释放锁
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
}
