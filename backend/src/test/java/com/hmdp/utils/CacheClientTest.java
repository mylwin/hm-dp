package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheClientTest {
    private final String key = CACHE_SHOP_KEY + 1L;
    private CacheClient client;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedissonClient redisson;
    private RLock lock;
    private Function<Long, Shop> database;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = new CacheClient();
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        database = mock(Function.class);
        ReflectionTestUtils.setField(client, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(client, "redissonClient", redisson);
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.getLock(LOCK_KEY + "cache:mutex:" + key)).thenReturn(lock);
    }

    private Shop query() {
        return client.queryWithMutex(CACHE_SHOP_KEY, 1L, Shop.class,
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
                new WebExceptionAdvice().handleCacheRebuildBusyException(exception).getErrorMsg());
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
}
