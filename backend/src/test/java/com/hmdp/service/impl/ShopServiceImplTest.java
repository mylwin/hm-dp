package com.hmdp.service.impl;

import com.hmdp.config.CacheProperties;
import com.hmdp.config.ShopBloomProperties;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.exception.CacheRebuildBusyException;
import com.hmdp.infrastructure.cache.RedisCacheClient;
import com.hmdp.infrastructure.cache.bloom.ShopBloomFilter;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RBucket;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.CacheConstants.CACHE_SHOP_KEY;
import static com.hmdp.constants.ShopConstants.SHOP_BLOOM_STATE_KEY_PREFIX;
import static com.hmdp.constants.ShopConstants.SHOP_BLOOM_LIFECYCLE_LOCK;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShopServiceImplTest {
    private ShopMapper mapper;
    private ShopBloomFilter bloom;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedissonClient redisson;
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
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        mapper = mock(ShopMapper.class);
        bloom = mock(ShopBloomFilter.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        redisson = mock(RedissonClient.class);
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
        ReflectionTestUtils.setField(cacheClient, "stringRedisTemplate", redis);
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

    /** 验证无效查询编号在接触缓存、锁和数据库之前返回参数错误。 */
    @Test
    void invalidQueryIdsDoNotAccessDependencies() {
        assertFalse(service.queryById(null).getSuccess());
        assertFalse(service.queryById(0L).getSuccess());
        assertFalse(service.queryById(-1L).getSuccess());
        verifyNoInteractions(mapper, bloom, redis, redisson, values);
    }

    /** 验证布隆拒绝在实际业务链路中返回不存在，不加锁、不查数据库、不写空值。 */
    @Test
    void rejectedShopQueryReturnsNotFoundWithoutFallback() {
        when(bloom.allowCacheMiss(42L)).thenReturn(false);
        Result result = service.queryById(42L);
        assertFalse(result.getSuccess());
        assertEquals("店铺不存在！", result.getErrorMsg());
        verify(bloom, times(1)).allowCacheMiss(42L);
        verifyNoInteractions(mapper, redisson);
        verify(values).get(CACHE_SHOP_KEY + 42);
        verifyNoMoreInteractions(values);
    }

    /** 验证正常缓存与空值缓存命中均不会调用布隆准入。 */
    @Test
    void shopCacheHitsSkipBloomAdmission() {
        Shop shop = new Shop();
        shop.setId(42L);
        when(values.get(CACHE_SHOP_KEY + 42)).thenReturn(JSONUtil.toJsonStr(shop), "");
        Result cached = service.queryById(42L);
        assertTrue(cached.getSuccess());
        assertEquals(42L, ((Shop) cached.getData()).getId());
        assertFalse(service.queryById(42L).getSuccess());
        verifyNoInteractions(bloom, mapper, redisson);
    }

    /** 验证布隆可能存在但数据库无记录时，仍由空值缓存兜底。 */
    @Test
    void bloomFalsePositiveStillUsesDatabaseAndNegativeCache() {
        when(bloom.allowCacheMiss(42L)).thenReturn(true);
        assertFalse(service.queryById(42L).getSuccess());
        verify(mapper).selectById(42L);
        verify(values).set(eq(CACHE_SHOP_KEY + 42), eq(""),
                longThat(ttl -> ttl >= 120 && ttl <= 150), eq(TimeUnit.SECONDS));
        verify(detail).unlock();
    }

    /** 验证准入通过后可返回数据库中的真实商铺并回填正常缓存。 */
    @Test
    void admittedExistingShopReturnsDatabaseRecord() {
        Shop shop = new Shop();
        shop.setId(42L);
        when(bloom.allowCacheMiss(42L)).thenReturn(true);
        when(mapper.selectById(42L)).thenReturn(shop);
        Result result = service.queryById(42L);
        assertTrue(result.getSuccess());
        assertSame(shop, result.getData());
        verify(values).set(eq(CACHE_SHOP_KEY + 42), eq(JSONUtil.toJsonStr(shop)),
                longThat(ttl -> ttl >= 1800 && ttl <= 2100), eq(TimeUnit.SECONDS));
    }

    /**
     * 验证第二阶段新增同步后，第三阶段启用的真实布隆准入逻辑不会拒绝新商铺。
     * 布隆位集合、数据库和 Redis 使用模拟对象，不代表真实依赖联调。
     */
    @Test
    @SuppressWarnings("unchecked")
    void newlyCreatedShopPassesEnforcedBloomAdmission() {
        ShopBloomProperties properties = new ShopBloomProperties();
        properties.setEnforce(true);
        RBucket<String> state = mock(RBucket.class);
        RBloomFilter<Long> filter = mock(RBloomFilter.class);
        when(redisson.<String>getBucket(SHOP_BLOOM_STATE_KEY_PREFIX + "v1", StringCodec.INSTANCE))
                .thenReturn(state);
        when(redisson.<Long>getBloomFilter("snapshot-1", LongCodec.INSTANCE)).thenReturn(filter);
        when(state.get()).thenReturn(JSONUtil.toJsonStr(Map.of(
                "status", "READY", "syncProtocol", 2, "filterName", "snapshot-1",
                "expectedInsertions", 100_000, "falseProbability", 0.001)));
        when(filter.isExists()).thenReturn(true);
        Set<Long> members = new HashSet<>();
        when(filter.add(anyLong())).thenAnswer(call -> members.add(call.getArgument(0)));
        when(filter.contains(anyLong())).thenAnswer(call -> members.contains(call.getArgument(0)));
        var readWriteLock = mock(RReadWriteLock.class);
        when(redisson.getReadWriteLock(SHOP_BLOOM_LIFECYCLE_LOCK))
                .thenReturn(readWriteLock);
        when(readWriteLock.readLock()).thenReturn(lifecycle);
        ShopBloomFilter realBloom = new ShopBloomFilter(properties, redisson);
        ReflectionTestUtils.setField(service, "shopBloomFilter", realBloom);
        Shop shop = new Shop();

        assertTrue(service.createShop(shop).getSuccess());
        assertTrue(events.contains("commit"));
        when(mapper.selectById(42L)).thenReturn(shop);
        Result result = service.queryById(42L);

        assertTrue(result.getSuccess());
        assertSame(shop, result.getData());
        assertEquals(0, realBloom.rejectedCount());
        verify(filter).add(42L);
        verify(filter).contains(42L);
    }
}
