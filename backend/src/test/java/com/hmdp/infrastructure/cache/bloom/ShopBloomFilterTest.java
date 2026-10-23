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
}
