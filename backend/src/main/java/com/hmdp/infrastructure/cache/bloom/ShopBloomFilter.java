package com.hmdp.infrastructure.cache.bloom;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.ShopBloomProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

import static com.hmdp.constants.ShopConstants.*;

/**
 * 商铺 ID 快照、新增同步与查询准入，默认仅观察，可配置启用负向拦截。
 *
 * @author myl
 */
@Slf4j
@Component
public class ShopBloomFilter {
    /**
     * 布隆过滤器配置属性
     */
    private final ShopBloomProperties properties;
    /**
     * Redisson客户端
     */
    private final RedissonClient redisson;
    /**
     * 可能存在计数器
     */
    private final LongAdder mightExist = new LongAdder();
    /**
     * 不存在计数器
     */
    private final LongAdder absent = new LongAdder();
    /**
     * 未就绪计数器
     */
    private final LongAdder notReady = new LongAdder();
    /**
     * 失败计数器
     */
    private final LongAdder failures = new LongAdder();
    /**
     * 仅累计当前实例实际阻止继续查询的次数，不混用负向观察次数。
     */
    private final LongAdder rejected = new LongAdder();
    /**
     * 当前活动的过滤器句柄
     */
    private volatile FilterHandle activeFilter;

    /**
     * 过滤器句柄，包含名称与对象的不可变组合。
     */
    private record FilterHandle(String name, RBloomFilter<Long> filter) {
    }

    /**
     * 构造器注入，依赖布隆过滤器配置属性和Redisson客户端
     *
     * @param properties 布隆过滤器配置属性
     * @param redisson   Redisson客户端
     */
    public ShopBloomFilter(ShopBloomProperties properties, RedissonClient redisson) {
        this.properties = properties;
        this.redisson = redisson;
    }

    /**
     * 观察结果枚举
     * <p>
     * DISABLED: 布隆过滤器未启用<br>
     * NOT_READY: 布隆过滤器未就绪<br>
     * MIGHT_EXIST: 可能存在<br>
     * ABSENT: 当前快照判断不包含，不代表当前数据库一定不存在<br>
     * FAILED: 观察失败
     * </p>
     */
    public enum Observation {DISABLED, NOT_READY, MIGHT_EXIST, ABSENT, FAILED}

    /**
     * 计数为当前实例累计值，可供调试和后续监控接入，不逐请求打印日志。
     */
    public record Statistics(long mightExist, long absent, long notReady, long failures) {
    }

    /**
     * 获取统计信息
     *
     * @return 统计信息
     */
    public Statistics statistics() {
        return new Statistics(mightExist.sum(), absent.sum(), notReady.sum(), failures.sum());
    }

    /**
     * 判断缓存未命中请求是否可以继续执行原有查询，同时记录一次布隆观察。
     * 只有拦截开启且就绪快照明确不包含该 ID 时拒绝，其他结果均继续查询。
     *
     * @param id 缓存未命中的商铺正数编号，无效编号无法产生可拦截的负向结果
     * @return false 表示本次请求被布隆拦截，true 表示继续互斥缓存查询流程
     */
    public boolean allowCacheMiss(Long id) {
        Observation observation = observe(id);
        // 如果开启拦截且观察结果为ABSENT，则拒绝
        if (properties.isEnforce() && observation == Observation.ABSENT) {
            // 统计拦截次数
            rejected.increment();
            return false;
        }
        return true;
    }

    /**
     * 获取当前实例实际发生的布隆负向拦截次数。
     *
     * @return 自实例启动起累计的拦截次数，观察模式下不会增加
     */
    public long rejectedCount() {
        return rejected.sum();
    }

    /**
     * 观察给定的ID是否存在
     *
     * @param id ID
     * @return 观察结果
     */
    public Observation observe(Long id) {
        // 如果布隆过滤器未启用，则直接返回DISABLED
        if (!properties.isEnabled()) {
            return Observation.DISABLED;
        }
        // 尝试获取快照
        try {
            JSONObject snapshot = readySnapshot();
            // 如果ID为null或小于等于0，或者快照为null，则notReady计数器加1并返回NOT_READY
            if (id == null || id <= 0 || snapshot == null) {
                notReady.increment();
                return Observation.NOT_READY;
            }
            // 获取过滤器
            RBloomFilter<Long> filter = filter(snapshot.getStr("filterName"));
            // 如果过滤器不存在，则notReady计数器加1并返回NOT_READY
            if (!filter.isExists()) {
                notReady.increment();
                return Observation.NOT_READY;
            }
            // 如果过滤器包含该ID，则mightExist计数器加1并返回MIGHT_EXIST
            if (filter.contains(id)) {
                mightExist.increment();
                return Observation.MIGHT_EXIST;
            }
            // 如果过滤器不包含该ID，则absent计数器加1并返回ABSENT
            absent.increment();
            return Observation.ABSENT;
        } catch (RuntimeException e) {
            // 可选观察失败不影响原有查询；避免大量无效请求产生逐请求错误日志。
            failures.increment();
            return Observation.FAILED;
        }
    }

    /**
     * 是否有就绪的快照
     *
     * @return 如果有就绪的快照则返回true，否则返回false
     */
    boolean hasReadySnapshot() {
        JSONObject snapshot = readySnapshot();
        // 如果快照不为null且过滤器存在，则返回true，否则返回false
        return snapshot != null && filter(snapshot.getStr("filterName")).isExists();
    }

    /**
     * 获取过滤器
     *
     * @param name 过滤器名称
     * @return 过滤器
     */
    private RBloomFilter<Long> filter(String name) {
        // 尝试从缓存中获取过滤器
        FilterHandle cached = activeFilter;
        // 如果缓存的过滤器名称与要获取的过滤器名称相同，则返回缓存的过滤器
        if (cached != null && cached.name().equals(name)) {
            return cached.filter();
        }
        // 从Redisson中获取过滤器
        RBloomFilter<Long> filter = redisson.getBloomFilter(name, LongCodec.INSTANCE);
        // 缓存名称与对象的不可变组合，避免每次观察都重新读取Redisson过滤器配置。
        activeFilter = new FilterHandle(name, filter);
        return filter;
    }

    /**
     * 尝试获取就绪的快照
     *
     * @return 就绪的快照，或null
     */
    private JSONObject readySnapshot() {
        // 从Redis中获取布隆过滤器状态
        String json = state().get();
        if (json == null) {
            return null;
        }
        JSONObject snapshot = JSONUtil.parseObj(json);
        // 如果状态不是READY
        // 或者预期插入数量不匹配
        // 或者概率不匹配
        // 或者过滤器名称为空，则返回null
        if (!"READY".equals(snapshot.getStr("status"))
                || !Integer.valueOf(SHOP_BLOOM_SYNC_PROTOCOL).equals(snapshot.getInt("syncProtocol"))
                || !Long.valueOf(properties.getExpectedInsertions()).equals(snapshot.getLong("expectedInsertions"))
                || !Double.valueOf(properties.getFalseProbability()).equals(snapshot.getDouble("falseProbability"))
                || snapshot.getStr("filterName") == null) {
            return null;
        }
        return snapshot;
    }

    /**
     * 获取初始化锁
     *
     * @return 初始化锁
     */
    RLock initializationLock() {
        return redisson.getReadWriteLock(SHOP_BLOOM_LIFECYCLE_LOCK).writeLock();
    }

    /**
     * 新增持读锁直到事务提交及缓存清理结束；不同商铺新增可并行。
     */
    public RLock creationLock() {
        return redisson.getReadWriteLock(SHOP_BLOOM_LIFECYCLE_LOCK).readLock();
    }

    /**
     * 在数据库提交前同步当前就绪过滤器；调用方必须持有creationLock。
     */
    public void addBeforeCommit(Long id) {
        if (!properties.isEnabled()) {
            return;
        }
        JSONObject snapshot = readySnapshot();
        if (snapshot == null) {
            // 尚无第二阶段快照，后续构建会在排斥新增的写锁内扫描已提交ID。
            return;
        }
        RBloomFilter<Long> current = filter(snapshot.getStr("filterName"));
        if (!current.isExists()) {
            throw new IllegalStateException("商铺布隆快照缺失，新增事务取消");
        }
        // 返回false也可能是位已设置（包括误判），不代表同步失败。
        current.add(id);
    }

    /**
     * 创建一个新的候选过滤器
     *
     * @return 新的候选过滤器
     */
    RBloomFilter<Long> newCandidate() {
        String name = SHOP_BLOOM_KEY_PREFIX + properties.getVersion() + ":" + UUID.randomUUID();
        return redisson.getBloomFilter(name, LongCodec.INSTANCE);
    }

    /**
     * 标记正在构建中
     */
    void markBuilding() {
        state().set(JSONUtil.toJsonStr(Map.of("status", "BUILDING")));
    }

    /**
     * 发布就绪的过滤器
     *
     * @param candidate   候选过滤器
     * @param loadedCount 加载的元素数量
     * @param upperBound  上界
     */
    void publishReady(RBloomFilter<Long> candidate, long loadedCount, long upperBound) {
        // 完整元数据用一次SET发布，不把构建中的过滤器名称暴露给查询端。
        state().set(JSONUtil.toJsonStr(Map.of(
                "status", "READY",
                "syncProtocol", SHOP_BLOOM_SYNC_PROTOCOL,
                "filterName", candidate.getName(),
                "expectedInsertions", properties.getExpectedInsertions(),
                "falseProbability", properties.getFalseProbability(),
                "loadedCount", loadedCount,
                "upperBound", upperBound,
                "builtAt", Instant.now().toString())));
        log.info("商铺布隆快照已就绪：version={}, loadedCount={}, upperBound={}, enforce={}",
                properties.getVersion(), loadedCount, upperBound, properties.isEnforce());
    }

    /**
     * 标记失败
     */
    void markFailed() {
        state().set(JSONUtil.toJsonStr(Map.of("status", "FAILED")));
    }

    /**
     * 获取布隆过滤器状态
     *
     * @return 布隆过滤器状态
     */
    private RBucket<String> state() {
        // 布隆过滤器状态存储在Redis中，键为"shop:bloom:state:v1"，值为JSON字符串
        return redisson.getBucket(SHOP_BLOOM_STATE_KEY_PREFIX + properties.getVersion(), StringCodec.INSTANCE);
    }
}
