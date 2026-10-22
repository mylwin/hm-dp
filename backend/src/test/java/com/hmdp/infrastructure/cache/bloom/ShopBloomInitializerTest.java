package com.hmdp.infrastructure.cache.bloom;

import com.hmdp.config.ShopBloomProperties;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopBloomInitializerTest {
    private ShopBloomProperties properties;
    private ShopBloomFilter bloom;
    private ShopMapper mapper;
    private RLock lock;
    private RBloomFilter<Long> candidate;
    private ShopBloomInitializer initializer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        properties = new ShopBloomProperties();
        properties.setBatchSize(2);
        bloom = mock(ShopBloomFilter.class);
        mapper = mock(ShopMapper.class);
        lock = mock(RLock.class);
        candidate = mock(RBloomFilter.class);
        when(bloom.initializationLock()).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(bloom.newCandidate()).thenReturn(candidate);
        when(candidate.tryInit(100_000, 0.001)).thenReturn(true);
        initializer = new ShopBloomInitializer(properties, bloom, mapper);
    }

    @Test
    void disabledSkipsAllInitialization() {
        properties.setEnabled(false);
        initializer.run(null);
        verifyNoInteractions(bloom, mapper);
    }

    @Test
    void anotherInstanceBuildingDoesNotStartSecondScan() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);
        initializer.run(null);
        verifyNoInteractions(mapper, candidate);
        verify(bloom, never()).markBuilding();
        verify(lock, never()).unlock();
    }

    @Test
    void existingReadySnapshotIsReused() {
        when(bloom.hasReadySnapshot()).thenReturn(true);
        initializer.run(null);
        verifyNoInteractions(mapper, candidate);
        verify(bloom, never()).markBuilding();
        verify(lock).unlock();
    }

    @Test
    void preCheckFailureDoesNotOverwriteReadyState() {
        when(bloom.hasReadySnapshot()).thenThrow(new IllegalStateException("redis unavailable"));
        initializer.run(null);
        verify(bloom, never()).markBuilding();
        verify(bloom, never()).markFailed();
        verify(bloom, never()).newCandidate();
        verify(lock).unlock();
    }

    @Test
    void cursorLoadsAllBatchesBeforePublishing() {
        when(mapper.selectMaxShopIdForBloom()).thenReturn(9L);
        when(mapper.selectShopIdsForBloom(0, 9, 2)).thenReturn(List.of(2L, 7L));
        when(mapper.selectShopIdsForBloom(7, 9, 2)).thenReturn(List.of(9L));
        initializer.run(null);
        var order = inOrder(bloom, candidate, mapper);
        order.verify(bloom).markBuilding();
        order.verify(candidate).tryInit(100_000, 0.001);
        order.verify(mapper).selectMaxShopIdForBloom();
        order.verify(mapper).selectShopIdsForBloom(0, 9, 2);
        order.verify(candidate).add(List.of(2L, 7L));
        order.verify(mapper).selectShopIdsForBloom(7, 9, 2);
        order.verify(candidate).add(List.of(9L));
        order.verify(bloom).publishReady(candidate, 3, 9);
        verify(candidate, never()).delete();
        verify(lock).unlock();
    }

    @Test
    void emptyDatabasePublishesEmptySnapshot() {
        initializer.run(null);
        verify(mapper, never()).selectShopIdsForBloom(anyLong(), anyLong(), anyInt());
        verify(bloom).publishReady(candidate, 0, 0);
    }

    @Test
    void databaseFailureNeverPublishesPartialSnapshot() {
        when(mapper.selectMaxShopIdForBloom()).thenReturn(9L);
        when(mapper.selectShopIdsForBloom(0, 9, 2)).thenReturn(List.of(2L, 7L));
        when(mapper.selectShopIdsForBloom(7, 9, 2)).thenThrow(new IllegalStateException("database unavailable"));
        initializer.run(null);
        verify(bloom, never()).publishReady(any(), anyLong(), anyLong());
        verify(bloom).markFailed();
        verify(candidate).delete();
        verify(lock).unlock();
    }

    @Test
    void overCapacityDoesNotPublishMisleadingReadyState() {
        properties.setExpectedInsertions(1);
        when(candidate.tryInit(1, 0.001)).thenReturn(true);
        when(mapper.selectMaxShopIdForBloom()).thenReturn(2L);
        when(mapper.selectShopIdsForBloom(0, 2, 2)).thenReturn(List.of(1L, 2L));
        initializer.run(null);
        verify(bloom, never()).publishReady(any(), anyLong(), anyLong());
        verify(bloom).markFailed();
        verify(candidate).delete();
    }

    @Test
    void invalidCursorCannotLoopForever() {
        when(mapper.selectMaxShopIdForBloom()).thenReturn(2L);
        when(mapper.selectShopIdsForBloom(0, 2, 2)).thenReturn(List.of(0L));
        initializer.run(null);
        verify(bloom).markFailed();
        verify(candidate).delete();
        verify(bloom, never()).publishReady(any(), anyLong(), anyLong());
    }

    @Test
    void interruptedLockWaitRestoresInterruptFlag() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try {
            initializer.run(null);
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(mapper, candidate);
            verify(lock, never()).unlock();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cleanupFailureCannotPreventApplicationStartup() {
        when(mapper.selectMaxShopIdForBloom()).thenThrow(new IllegalStateException("database unavailable"));
        doThrow(new IllegalStateException("cleanup unavailable")).when(candidate).delete();
        doThrow(new IllegalStateException("unlock unavailable")).when(lock).unlock();
        assertDoesNotThrow(() -> initializer.run(null));
        verify(bloom).markFailed();
        verify(bloom, never()).publishReady(any(), anyLong(), anyLong());
    }
}
