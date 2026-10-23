package com.hmdp.infrastructure.cache.bloom;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.ShopBloomProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;

import java.util.Map;

import static com.hmdp.constants.ShopConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopBloomFilterTest {
    private final ShopBloomProperties properties = new ShopBloomProperties();
    private RedissonClient redisson;
    private RBucket<String> state;
    private RBloomFilter<Long> filter;
    private ShopBloomFilter bloom;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        state = mock(RBucket.class);
        filter = mock(RBloomFilter.class);
        when(redisson.<String>getBucket(SHOP_BLOOM_STATE_KEY_PREFIX + "v1", StringCodec.INSTANCE)).thenReturn(state);
        when(redisson.<Long>getBloomFilter(anyString(), eq(LongCodec.INSTANCE))).thenReturn(filter);
        bloom = new ShopBloomFilter(properties, redisson);
    }

    private String ready(long capacity) {
        return JSONUtil.toJsonStr(Map.of("status", "READY", "syncProtocol", 2, "filterName", "snapshot-1",
                "expectedInsertions", capacity, "falseProbability", 0.001));
    }

    @Test
    void disabledDoesNotAccessRedis() {
        properties.setEnabled(false);
        assertEquals(ShopBloomFilter.Observation.DISABLED, bloom.observe(1L));
        verifyNoInteractions(redisson);
    }

    @Test
    void incompleteSnapshotIsNeverConsulted() {
        when(state.get()).thenReturn(null, "{\"status\":\"BUILDING\"}", "{\"status\":\"FAILED\"}");
        for (int i = 0; i < 3; i++) {
            assertEquals(ShopBloomFilter.Observation.NOT_READY, bloom.observe(1L));
        }
        verifyNoInteractions(filter);
        assertEquals(3, bloom.statistics().notReady());
    }

    @Test
    void configurationMismatchAndDeletedFilterAreNotReady() {
        when(state.get()).thenReturn(ready(1), ready(100_000));
        assertEquals(ShopBloomFilter.Observation.NOT_READY, bloom.observe(1L));
        assertEquals(ShopBloomFilter.Observation.NOT_READY, bloom.observe(1L));
        verify(filter, never()).contains(anyLong());
    }

    @Test
    void observesBothOutcomesWithoutRejectingOrChangingFilter() {
        when(state.get()).thenReturn(ready(100_000));
        when(filter.isExists()).thenReturn(true);
        when(filter.contains(1L)).thenReturn(true);
        when(filter.contains(2L)).thenReturn(false);
        assertEquals(ShopBloomFilter.Observation.MIGHT_EXIST, bloom.observe(1L));
        assertEquals(ShopBloomFilter.Observation.ABSENT, bloom.observe(2L));
        assertEquals(1, bloom.statistics().mightExist());
        assertEquals(1, bloom.statistics().absent());
        verify(redisson, times(1)).getBloomFilter("snapshot-1", LongCodec.INSTANCE);
        verify(filter, never()).add(anyLong());
        verify(state, never()).set(anyString());
    }

    @Test
    void optionalObservationFailureDoesNotEscapeToBusinessQuery() {
        when(state.get()).thenThrow(new IllegalStateException("observation unavailable"));
        assertEquals(ShopBloomFilter.Observation.FAILED, bloom.observe(1L));
        assertEquals(1, bloom.statistics().failures());
    }

    @Test
    void publishesCompleteMetadataInOneWrite() {
        when(filter.getName()).thenReturn("snapshot-1");
        bloom.publishReady(filter, 12, 20);
        verify(state).set(argThat(json -> {
            var data = JSONUtil.parseObj(json);
            return "READY".equals(data.getStr("status"))
                    && "snapshot-1".equals(data.getStr("filterName"))
                    && data.getLong("loadedCount") == 12L
                    && data.getLong("upperBound") == 20L
                    && data.getLong("expectedInsertions") == 100_000L;
        }));
    }

    @Test
    void legacySnapshotWithoutSynchronizationProtocolIsNotReused() {
        when(state.get()).thenReturn(ready(100_000).replace("\"syncProtocol\":2,", "")
                .replace(",\"syncProtocol\":2", ""));
        assertEquals(ShopBloomFilter.Observation.NOT_READY, bloom.observe(1L));
        assertFalse(bloom.hasReadySnapshot());
    }

    @Test
    void addsNewIdToReadySnapshotBeforeCommit() {
        when(state.get()).thenReturn(ready(100_000));
        when(filter.isExists()).thenReturn(true);
        bloom.addBeforeCommit(42L);
        // add返回false可能仅表示位已存在，不应错误取消数据库事务。
        verify(filter).add(42L);
    }

    @Test
    void noReadySnapshotDefersInclusionToNextFullScan() {
        when(state.get()).thenReturn("{\"status\":\"BUILDING\"}");
        bloom.addBeforeCommit(42L);
        verifyNoInteractions(filter);
    }

    @Test
    void synchronizationErrorEscapesSoCallerCanRollBackDatabase() {
        when(state.get()).thenReturn(ready(100_000));
        when(filter.isExists()).thenReturn(true);
        when(filter.add(42L)).thenThrow(new IllegalStateException("sync failed"));
        assertThrows(IllegalStateException.class, () -> bloom.addBeforeCommit(42L));
    }

    @Test
    void creationAndInitializationUseOppositeSidesOfSameGlobalLock() {
        var lifecycle = mock(org.redisson.api.RReadWriteLock.class);
        var read = mock(org.redisson.api.RLock.class);
        var write = mock(org.redisson.api.RLock.class);
        when(redisson.getReadWriteLock(SHOP_BLOOM_LIFECYCLE_LOCK)).thenReturn(lifecycle);
        when(lifecycle.readLock()).thenReturn(read);
        when(lifecycle.writeLock()).thenReturn(write);
        assertSame(read, bloom.creationLock());
        assertSame(write, bloom.initializationLock());
    }

    /** 验证观察模式中的负向结果仍允许继续查询，不累计实际拦截次数。 */
    @Test
    void observationModeAllowsNegativeResults() {
        when(state.get()).thenReturn(ready(100_000));
        when(filter.isExists()).thenReturn(true);
        assertTrue(bloom.allowCacheMiss(42L));
        assertEquals(1, bloom.statistics().absent());
        assertEquals(0, bloom.rejectedCount());
    }

    /** 验证启用后只在准入入口拦截负向结果，直接观察不会累计实际拦截次数。 */
    @Test
    void enforcedNegativeResultRejectsOnlyAdmissionCalls() {
        properties.setEnforce(true);
        when(state.get()).thenReturn(ready(100_000));
        when(filter.isExists()).thenReturn(true);
        assertEquals(ShopBloomFilter.Observation.ABSENT, bloom.observe(42L));
        assertEquals(0, bloom.rejectedCount());
        assertFalse(bloom.allowCacheMiss(42L));
        assertEquals(2, bloom.statistics().absent());
        assertEquals(1, bloom.rejectedCount());
        verify(filter, times(2)).contains(42L);
    }

    /** 验证可能包含的 ID 始终继续查询，留给数据库识别真实存在与误判。 */
    @Test
    void enforcedPossibleMatchContinuesQuery() {
        properties.setEnforce(true);
        when(state.get()).thenReturn(ready(100_000));
        when(filter.isExists()).thenReturn(true);
        when(filter.contains(42L)).thenReturn(true);
        assertTrue(bloom.allowCacheMiss(42L));
        assertEquals(1, bloom.statistics().mightExist());
        assertEquals(0, bloom.rejectedCount());
    }

    /** 验证组件关闭优先于拦截配置，不访问布隆状态。 */
    @Test
    void disabledBloomCannotRejectEvenWhenEnforcementIsConfigured() {
        properties.setEnabled(false);
        properties.setEnforce(true);
        assertTrue(bloom.allowCacheMiss(42L));
        assertEquals(0, bloom.rejectedCount());
        verifyNoInteractions(redisson);
    }

    /** 验证未就绪、旧协议、配置不匹配或过滤器缺失时都继续查询。 */
    @Test
    void enforcementCannotUseIncompleteOrIncompatibleSnapshots() {
        properties.setEnforce(true);
        var legacy = JSONUtil.parseObj(ready(100_000));
        legacy.remove("syncProtocol");
        when(state.get()).thenReturn(null, "{\"status\":\"BUILDING\"}", "{\"status\":\"FAILED\"}",
                ready(1), legacy.toString(), ready(100_000));
        for (int i = 0; i < 6; i++) {
            assertTrue(bloom.allowCacheMiss(42L));
        }
        assertEquals(6, bloom.statistics().notReady());
        assertEquals(0, bloom.rejectedCount());
        verify(filter, never()).contains(anyLong());
    }

    /** 验证沿用的观察异常处理不会被误当成负向拦截依据。 */
    @Test
    void observationFailureCannotBecomeARejection() {
        properties.setEnforce(true);
        when(state.get()).thenThrow(new IllegalStateException("observation unavailable"));
        assertTrue(bloom.allowCacheMiss(42L));
        assertEquals(1, bloom.statistics().failures());
        assertEquals(0, bloom.rejectedCount());
    }

    /** 验证构建记录使用独立键，不覆盖第三阶段查询需要的 READY 元数据。 */
    @Test
    @SuppressWarnings("unchecked")
    void buildStatusDoesNotReplaceActiveReadyMetadata() {
        RBucket<String> buildState = mock(RBucket.class);
        when(redisson.<String>getBucket(SHOP_BLOOM_BUILD_STATE_KEY_PREFIX + "v1", StringCodec.INSTANCE))
                .thenReturn(buildState);
        when(state.get()).thenReturn(ready(100_000));
        when(filter.isExists()).thenReturn(true);
        when(filter.contains(42L)).thenReturn(true);
        properties.setEnforce(true);

        bloom.recordBuildStatus("{\"status\":\"BUILDING\"}");
        assertTrue(bloom.allowCacheMiss(42L));
        when(buildState.get()).thenReturn("{\"status\":\"BUILDING\"}");
        assertEquals("{\"status\":\"BUILDING\"}", bloom.lastBuildStatus());

        verify(buildState).set("{\"status\":\"BUILDING\"}");
        verify(state, never()).set(anyString());
    }
}
