package com.hmdp.service.impl;

import com.hmdp.config.CacheProperties;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.exception.CacheRebuildBusyException;
import com.hmdp.infrastructure.cache.RedisCacheClient;
import com.hmdp.infrastructure.cache.bloom.ShopBloomFilter;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.CacheConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShopServiceImplTest {
    private ShopMapper mapper;
    private ShopBloomFilter bloom;
    private StringRedisTemplate redis;
    private RLock lifecycle;
    private RLock detail;
    private ShopServiceImpl service;
    private final List<String> events = new ArrayList<>();
    private final TestTransactions transactions = new TestTransactions();

    private class TestTransactions extends AbstractPlatformTransactionManager {
        /** {@inheritDoc} */
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        /** {@inheritDoc} */
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        /** {@inheritDoc} */
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        /** {@inheritDoc} */
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }

    /**
     * 配置商铺服务依赖，并记录事务、缓存和锁操作顺序。
     *
     * @throws Exception 配置锁模拟对象时发生异常
     */
    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(ShopMapper.class);
        bloom = mock(ShopBloomFilter.class);
        redis = mock(StringRedisTemplate.class);
        var redisson = mock(RedissonClient.class);
        lifecycle = mock(RLock.class);
        detail = mock(RLock.class);
        when(bloom.creationLock()).thenReturn(lifecycle);
        when(redisson.getLock("hmdp:lock:cache:mutex:shop:42")).thenReturn(detail);
        when(lifecycle.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(detail.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lifecycle.isHeldByCurrentThread()).thenReturn(true);
        when(detail.isHeldByCurrentThread()).thenReturn(true);
        doAnswer(call -> { events.add("detail-unlock"); return null; }).when(detail).unlock();
        doAnswer(call -> { events.add("lifecycle-unlock"); return null; }).when(lifecycle).unlock();
        when(mapper.insert(any(Shop.class))).thenAnswer(call -> {
            events.add("insert");
            ((Shop) call.getArgument(0)).setId(42L);
            return 1;
        });
        doAnswer(call -> { events.add("bloom"); return null; }).when(bloom).addBeforeCommit(42L);
        when(redis.delete(CACHE_SHOP_KEY + 42)).thenAnswer(call -> {
            assertTrue(events.contains("commit"));
            assertFalse(events.contains("detail-unlock"));
            events.add("cache-delete");
            return true;
        });
        service = new ShopServiceImpl();
        RedisCacheClient cacheClient = new RedisCacheClient(new CacheProperties());
        ReflectionTestUtils.setField(cacheClient, "redissonClient", redisson);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "shopBloomFilter", bloom);
        ReflectionTestUtils.setField(service, "redisCacheClient", cacheClient);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(transactions));
    }

    /** 验证布隆同步先于提交、缓存清理先于解锁，结果中包含新增编号。 */
    @Test
    void synchronizesBeforeCommitAndClearsNegativeCacheBeforeUnlock() {
        Result result = service.createShop(new Shop());
        assertTrue(result.getSuccess());
        assertEquals(42L, result.getData());
        assertEquals(List.of("insert", "bloom", "commit", "cache-delete", "detail-unlock", "lifecycle-unlock"), events);
    }

    /** 验证布隆同步异常导致事务回滚，并且不会清理缓存。 */
    @Test
    void bloomFailureRollsBackWithoutDeletingCache() {
        doThrow(new IllegalStateException("sync failed")).when(bloom).addBeforeCommit(42L);
        assertThrows(IllegalStateException.class, () -> service.createShop(new Shop()));
        assertEquals(List.of("insert", "rollback", "detail-unlock", "lifecycle-unlock"), events);
        verifyNoInteractions(redis);
    }

    /**
     * 验证生命周期锁繁忙时不启动数据库事务。
     *
     * @throws Exception 配置锁模拟对象时发生异常
     */
    @Test
    void busyRebuildLockDoesNotStartTransaction() throws Exception {
        when(lifecycle.tryLock(1, TimeUnit.SECONDS)).thenReturn(false);
        assertThrows(CacheRebuildBusyException.class, () -> service.createShop(new Shop()));
        assertTrue(events.isEmpty());
        verifyNoInteractions(mapper, redis);
        verify(lifecycle, never()).unlock();
    }

    /**
     * 验证详情锁繁忙时回滚插入，不执行布隆同步。
     *
     * @throws Exception 配置锁模拟对象时发生异常
     */
    @Test
    void busyDetailLockRollsBackAndDoesNotSynchronizeBloom() throws Exception {
        when(detail.tryLock(1, TimeUnit.SECONDS)).thenReturn(false);
        assertThrows(CacheRebuildBusyException.class, () -> service.createShop(new Shop()));
        assertEquals(List.of("insert", "rollback", "lifecycle-unlock"), events);
        verify(bloom, never()).addBeforeCommit(anyLong());
        verify(detail, never()).unlock();
    }

    /** 验证数据库提交失败时不清理缓存，仍释放已取得的两把锁。 */
    @Test
    void commitFailureLeavesOnlyHarmlessBloomFalsePositive() {
        var failing = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(failing.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        doThrow(new IllegalStateException("commit failed")).when(failing).commit(any());
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(failing));
        assertThrows(IllegalStateException.class, () -> service.createShop(new Shop()));
        verify(bloom).addBeforeCommit(42L);
        verifyNoInteractions(redis);
        verify(detail).unlock();
        verify(lifecycle).unlock();
    }

    /**
     * 验证等待详情锁时被中断会回滚，并恢复线程中断标记。
     *
     * @throws Exception 配置锁模拟对象时发生异常
     */
    @Test
    void interruptionRollsBackAndRestoresFlag() throws Exception {
        when(detail.tryLock(1, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try {
            assertThrows(CacheRebuildBusyException.class, () -> service.createShop(new Shop()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("insert", "rollback", "lifecycle-unlock"), events);
        } finally {
            Thread.interrupted();
        }
    }

    /** 验证入口拒绝加入外层事务，防止数据库提交之前释放锁。 */
    @Test
    void rejectsAmbientTransactionSoLockCannotBeReleasedBeforeCommit() {
        new TransactionTemplate(transactions).execute(status -> {
            assertThrows(IllegalStateException.class, () -> service.createShop(new Shop()));
            verifyNoInteractions(mapper, bloom, redis);
            return null;
        });
    }

    /** 验证无效商铺参数不会进入数据库、布隆过滤器或缓存处理。 */
    @Test
    void invalidShopCannotEnterTransactionOrBloom() {
        var shop = new Shop();
        shop.setId(-1L);
        assertThrows(IllegalArgumentException.class, () -> service.createShop(shop));
        assertThrows(IllegalArgumentException.class, () -> service.createShop(null));
        verifyNoInteractions(mapper, bloom, redis);
    }
}
