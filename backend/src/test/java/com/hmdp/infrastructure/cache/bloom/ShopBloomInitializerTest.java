package com.hmdp.infrastructure.cache.bloom;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.ShopBloomProperties;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /** 验证手动重建即使存在 READY 快照也扫描，并且构建期间不覆盖旧活动状态。 */
    @Test
    void manualRebuildReplacesReadySnapshotOnlyAfterFullScan() {
        when(bloom.hasReadySnapshot()).thenReturn(true);
        when(candidate.getName()).thenReturn("new-candidate");
        when(mapper.selectMaxShopIdForBloom()).thenReturn(3L);
        when(mapper.selectShopIdsForBloom(0, 3, 2)).thenReturn(List.of(1L, 2L));
        when(mapper.selectShopIdsForBloom(2, 3, 2)).thenReturn(List.of(3L));

        var result = JSONUtil.parseObj(initializer.rebuild());

        assertEquals("SUCCESS", result.getStr("status"));
        assertEquals(3L, result.getLong("loadedCount"));
        assertEquals(3L, result.getLong("upperBound"));
        assertEquals("new-candidate", result.getStr("filterName"));
        verify(bloom, never()).markBuilding();
        verify(bloom, never()).markFailed();
        var order = inOrder(candidate, bloom);
        order.verify(candidate).add(List.of(1L, 2L));
        order.verify(candidate).add(List.of(3L));
        order.verify(bloom).publishReady(candidate, 3, 3);
        verify(candidate, never()).delete();
        verify(lock).unlock();
        verify(bloom).recordBuildStatus(argThat(json -> "BUILDING".equals(JSONUtil.parseObj(json).getStr("status"))));
        verify(bloom).recordBuildStatus(argThat(json -> "SUCCESS".equals(JSONUtil.parseObj(json).getStr("status"))));
    }

    /** 验证手动重建失败只清理候选，不把已有 READY 标成 FAILED。 */
    @Test
    void failedManualRebuildPreservesOldReadySnapshot() {
        when(bloom.hasReadySnapshot()).thenReturn(true);
        when(mapper.selectMaxShopIdForBloom()).thenThrow(new IllegalStateException("scan failed"));

        var result = JSONUtil.parseObj(initializer.rebuild());

        assertEquals("FAILED", result.getStr("status"));
        assertNull(result.getLong("loadedCount"));
        verify(bloom, never()).markBuilding();
        verify(bloom, never()).markFailed();
        verify(bloom, never()).publishReady(any(), anyLong(), anyLong());
        verify(candidate).delete();
        verify(lock).unlock();
        verify(bloom).recordBuildStatus(argThat(json -> "FAILED".equals(JSONUtil.parseObj(json).getStr("status"))));
    }

    /** 验证没有旧快照的失败构建仍保留原有 FAILED 活动状态语义。 */
    @Test
    void failedFirstManualBuildMarksActiveStateFailed() {
        when(mapper.selectMaxShopIdForBloom()).thenThrow(new IllegalStateException("scan failed"));
        assertEquals("FAILED", JSONUtil.parseObj(initializer.rebuild()).getStr("status"));
        verify(bloom).markBuilding();
        verify(bloom).markFailed();
        verify(candidate).delete();
    }

    /**
     * 验证忙碌的手动调用不会覆盖其他构建的状态，也不会误解锁。
     *
     * @throws Exception 配置模拟锁调用时发生异常
     */
    @Test
    void busyManualCallDoesNotOverwriteBuildStatus() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);
        assertEquals("BUSY", JSONUtil.parseObj(initializer.rebuild()).getStr("status"));
        verify(bloom, never()).recordBuildStatus(anyString());
        verify(bloom, never()).markBuilding();
        verify(lock, never()).unlock();
        verifyNoInteractions(mapper, candidate);
    }

    /** 验证关闭组件时手动调用不触发构建和状态写入。 */
    @Test
    void disabledManualCallDoesNotAccessRedisOrDatabase() {
        properties.setEnabled(false);
        assertEquals("DISABLED", JSONUtil.parseObj(initializer.rebuild()).getStr("status"));
        verifyNoInteractions(bloom, mapper, candidate);
    }

    /** 验证存在活动事务时拒绝扫描，避免将未提交的数据视为完整快照。 */
    @Test
    void manualBuildRejectsAmbientTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals("FAILED", JSONUtil.parseObj(initializer.rebuild()).getStr("status"));
            verifyNoInteractions(bloom, mapper, candidate);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * 验证手动等锁中断返回可识别结果，并恢复中断标记。
     *
     * @throws Exception 配置模拟锁调用时发生异常
     */
    @Test
    void interruptedManualBuildRestoresFlagWithoutWritingState() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try {
            assertEquals("INTERRUPTED", JSONUtil.parseObj(initializer.rebuild()).getStr("status"));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(bloom, never()).recordBuildStatus(anyString());
        } finally {
            Thread.interrupted();
        }
    }

    /** 验证状态记录失败不能导致已发布的快照被标记失败或删除。 */
    @Test
    void buildRecordFailureCannotDeletePublishedSnapshot() {
        doThrow(new IllegalStateException("record failed")).when(bloom).recordBuildStatus(anyString());
        assertEquals("SUCCESS", JSONUtil.parseObj(initializer.rebuild()).getStr("status"));
        verify(bloom).publishReady(candidate, 0, 0);
        verify(bloom, never()).markFailed();
        verify(candidate, never()).delete();
    }

    /** 验证维护属性能区分尚无记录和已保存的构建结果。 */
    @Test
    void exposesLastBuildStatusWithoutStartingAnotherBuild() {
        assertEquals("NEVER", JSONUtil.parseObj(initializer.getLastBuildStatus()).getStr("status"));
        when(bloom.lastBuildStatus()).thenReturn("{\"status\":\"SUCCESS\"}");
        assertEquals("SUCCESS", JSONUtil.parseObj(initializer.getLastBuildStatus()).getStr("status"));
        verifyNoInteractions(mapper, candidate);
        verify(bloom, never()).initializationLock();
    }
}
