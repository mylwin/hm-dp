package com.hmdp.infrastructure.cache;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.CacheProperties;
import com.hmdp.infrastructure.cache.bloom.ShopBloomFilter;
import com.hmdp.entity.Shop;
import com.hmdp.exception.CacheRebuildBusyException;
import com.hmdp.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Consumer;

import static com.hmdp.constants.CacheConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisCacheClientTest {
    private final String key = CACHE_SHOP_KEY + 1L;
    private RedisCacheClient client;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedissonClient redisson;
    private RLock lock;
    private Function<Long, Shop> database;

    /** 配置缓存客户端及依赖，锁模拟仅接受去除重复命名空间后的标准锁键。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = new RedisCacheClient(new CacheProperties());
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        database = mock(Function.class);
        ReflectionTestUtils.setField(client, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(client, "redissonClient", redisson);
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.getLock("hmdp:lock:cache:mutex:shop:1")).thenReturn(lock);
    }

    /** 验证获取锁对象不会加锁，且资源内容和自定义缓存键不会被全局替换。 */
    @Test
    void cacheMutexLockPreservesResourceContentWithoutAcquiringLock() {
        assertSame(lock, client.getCacheMutexLock(key));
        client.getCacheMutexLock(CACHE_KEY_PREFIX + "shop:hmdp:cache:42");
        client.getCacheMutexLock("external:shop:42");

        verify(redisson).getLock("hmdp:lock:cache:mutex:shop:1");
        verify(redisson).getLock("hmdp:lock:cache:mutex:shop:hmdp:cache:42");
        verify(redisson).getLock("hmdp:lock:cache:mutex:external:shop:42");
        verifyNoInteractions(lock);
    }

    /** 验证没有资源标识的缓存键不会触发 Redis 锁访问。 */
    @Test
    void invalidCacheKeysCannotCreateMutexLocks() {
        assertThrows(IllegalArgumentException.class, () -> client.getCacheMutexLock(null));
        assertThrows(IllegalArgumentException.class, () -> client.getCacheMutexLock(" "));
        assertThrows(IllegalArgumentException.class, () -> client.getCacheMutexLock(CACHE_KEY_PREFIX));
        verifyNoInteractions(redisson);
    }

    private Shop query() {
        return query(1L);
    }

    private Shop query(Long id) {
        return client.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class,
                database, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    @Test
    void cacheHitDoesNotAcquireLockOrQueryDatabase() {
        Shop shop = new Shop();
        shop.setId(1L);
        when(values.get(key)).thenReturn(JSONUtil.toJsonStr(shop));
        assertEquals(1L, query().getId());
        verifyNoInteractions(redisson, database);
    }

    @Test
    void cachedEmptyValueDoesNotAcquireLockOrQueryDatabase() {
        when(values.get(key)).thenReturn("");
        assertNull(query());
        verifyNoInteractions(redisson, database);
    }

    @Test
    void timeoutReturnsBusyWithoutQueryingDatabaseOrUnlocking() throws Exception {
        CacheRebuildBusyException exception = assertThrows(CacheRebuildBusyException.class, this::query);
        assertEquals("系统繁忙，请稍后再试", exception.getMessage());
        verify(lock).tryLock(1, TimeUnit.SECONDS);
        verifyNoInteractions(database);
        verify(lock, never()).unlock();
        verify(redis, never()).delete(anyString());
        assertEquals(exception.getMessage(),
                new GlobalExceptionHandler().handleCacheRebuildBusyException(exception).getErrorMsg());
    }

    @Test
    void timeoutCanStillReadCacheRebuiltByAnotherRequest() {
        Shop shop = new Shop();
        shop.setId(1L);
        when(values.get(key)).thenReturn(null, JSONUtil.toJsonStr(shop));
        assertEquals(1L, query().getId());
        verifyNoInteractions(database);
        verify(lock, never()).unlock();
    }

    @Test
    void doubleCheckPreventsRepeatedDatabaseQuery() throws Exception {
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(values.get(key)).thenReturn(null, "");
        assertNull(query());
        verifyNoInteractions(database);
        verify(lock).unlock();
    }

    @Test
    void lockHolderRebuildsCacheWithRandomTtl() throws Exception {
        Shop shop = new Shop();
        shop.setId(1L);
        when(database.apply(1L)).thenReturn(shop);
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        assertSame(shop, query());
        verify(database, times(1)).apply(1L);
        verify(values).set(eq(key), eq(JSONUtil.toJsonStr(shop)),
                longThat(ttl -> ttl >= 1800 && ttl <= 2100), eq(TimeUnit.SECONDS));
        verify(lock).unlock();
    }

    @Test
    void missingDatabaseRecordKeepsEmptyValueProtection() throws Exception {
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        assertNull(query());
        verify(values).set(eq(key), eq(""),
                longThat(ttl -> ttl >= 120 && ttl <= 150), eq(TimeUnit.SECONDS));
        verify(lock).unlock();
    }

    @Test
    void databaseFailureReleasesLockWithoutWrappingOriginalException() throws Exception {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(database.apply(1L)).thenThrow(failure);
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        assertSame(failure, assertThrows(IllegalStateException.class, this::query));
        verify(lock).unlock();
    }

    @Test
    void noLongerOwnedLockIsNotUnlocked() throws Exception {
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        assertNull(query());
        verify(lock, never()).unlock();
        verify(redis, never()).delete(anyString());
    }

    @Test
    void interruptionPreservesFlagAndDoesNotQueryDatabase() throws Exception {
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try {
            assertThrows(CacheRebuildBusyException.class, this::query);
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(database);
            verify(lock, never()).unlock();
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * 验证不同商铺共享回源额度，额度耗尽时缓存命中仍不需要获取缓存锁。
     *
     * @throws Exception 模拟锁调用或并发任务等待时发生异常
     */
    @Test
    void differentShopsShareLimitWhileCachedRequestsStillSucceed() throws Exception {
        CacheProperties properties = new CacheProperties();
        properties.setMaxConcurrentFallbacks(2);
        client = new RedisCacheClient(properties);
        ReflectionTestUtils.setField(client, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(client, "redissonClient", redisson);
        // 模拟不同商铺都能拿到各自的锁，验证跨Key的本地并发限制。
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        CountDownLatch enteredDatabase = new CountDownLatch(2);
        CountDownLatch finishQueries = new CountDownLatch(1);
        when(database.apply(anyLong())).thenAnswer(invocation -> {
            enteredDatabase.countDown();
            if (!finishQueries.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("数据库查询未收到完成信号");
            }
            Shop shop = new Shop();
            shop.setId(invocation.getArgument(0));
            return shop;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Shop> first = executor.submit(() -> query(1L));
            Future<Shop> second = executor.submit(() -> query(2L));
            assertTrue(enteredDatabase.await(5, TimeUnit.SECONDS));

            assertThrows(CacheRebuildBusyException.class, () -> query(3L));
            verify(database, never()).apply(3L);
            verify(values, never()).set(eq(CACHE_SHOP_KEY + 3L), anyString(), anyLong(), any(TimeUnit.class));
            // 额度已满时，正常缓存和空值缓存均不需要申请许可。
            Shop cached = new Shop();
            cached.setId(99L);
            when(values.get(CACHE_SHOP_KEY + 99L)).thenReturn(JSONUtil.toJsonStr(cached));
            when(values.get(CACHE_SHOP_KEY + 100L)).thenReturn("");
            assertEquals(99L, query(99L).getId());
            assertNull(query(100L));
            verify(redisson, never()).getLock("hmdp:lock:cache:mutex:shop:99");
            verify(redisson, never()).getLock("hmdp:lock:cache:mutex:shop:100");

            finishQueries.countDown();
            assertEquals(1L, first.get(5, TimeUnit.SECONDS).getId());
            assertEquals(2L, second.get(5, TimeUnit.SECONDS).getId());
            assertEquals(4L, query(4L).getId());
            // 两次并发成功、一次拒绝、一次后续成功，均释放商铺锁。
            verify(lock, times(4)).unlock();
        } finally {
            finishQueries.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void emptyResultAndDatabaseExceptionBothReturnPermit() throws Exception {
        CacheProperties properties = new CacheProperties();
        properties.setMaxConcurrentFallbacks(1);
        client = new RedisCacheClient(properties);
        ReflectionTestUtils.setField(client, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(client, "redissonClient", redisson);
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        Shop shop = new Shop();
        shop.setId(1L);
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(database.apply(1L)).thenReturn(null).thenThrow(failure).thenReturn(shop);

        assertNull(query());
        assertSame(failure, assertThrows(IllegalStateException.class, this::query));
        assertSame(shop, query());
        verify(lock, times(3)).unlock();
    }

    @Test
    void bloomNegativeObservationCannotRejectExistingShop() throws Exception {
        ShopBloomFilter bloom = mock(ShopBloomFilter.class);
        when(bloom.observe(1L)).thenReturn(ShopBloomFilter.Observation.ABSENT);
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        Shop shop = new Shop();
        shop.setId(1L);
        when(database.apply(1L)).thenReturn(shop);
        Shop result = client.queryWithMutex(CACHE_SHOP_KEY, 1L, Shop.class,
                database, CACHE_SHOP_TTL, TimeUnit.MINUTES, bloom::observe);
        assertSame(shop, result);
        verify(bloom).observe(1L);
        verify(database).apply(1L);
        verify(lock).unlock();
    }

    @Test
    @SuppressWarnings("unchecked")
    void cachedDataAndEmptyMarkerSkipObserver() {
        Consumer<Long> observer = mock(Consumer.class);
        Shop shop = new Shop();
        shop.setId(1L);
        when(values.get(key)).thenReturn(JSONUtil.toJsonStr(shop), "");
        assertEquals(1L, client.queryWithMutex(CACHE_SHOP_KEY, 1L, Shop.class,
                database, CACHE_SHOP_TTL, TimeUnit.MINUTES, observer).getId());
        assertNull(client.queryWithMutex(CACHE_SHOP_KEY, 1L, Shop.class,
                database, CACHE_SHOP_TTL, TimeUnit.MINUTES, observer));
        verifyNoInteractions(observer, database, redisson);
    }
}
