package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.CacheConstants.*;
import static com.hmdp.constants.LockConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopTypeServiceImplTest {
    private ShopTypeServiceImpl service;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private RedissonClient redisson;
    private RLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = spy(new ShopTypeServiceImpl());
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redisson.getLock(LOCK_KEY + "shop:type")).thenReturn(lock);
        when(zset.range(CACHE_SHOP_TYPE_KEY, 0, -1)).thenReturn(Collections.emptySet());
    }

    @Test
    void cachedEmptyMarkerDoesNotQueryDatabaseOrLock() {
        when(zset.range(CACHE_SHOP_TYPE_KEY, 0, -1)).thenReturn(Set.of(""));
        assertEquals("商铺类型不存在", service.queryList().getErrorMsg());
        verify(service, never()).list();
        verifyNoInteractions(redisson);
    }

    @Test
    void rechecksCacheAfterAcquiringLock() throws Exception {
        ShopType type = new ShopType();
        type.setId(1L);
        type.setName("美食");
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(zset.range(CACHE_SHOP_TYPE_KEY, 0, -1))
                .thenReturn(Collections.emptySet(), Set.of(JSONUtil.toJsonStr(type)));

        Result result = service.queryList();

        assertTrue(result.getSuccess());
        assertEquals(1, ((List<?>) result.getData()).size());
        verify(service, never()).list();
        verify(lock).unlock();
    }

    @Test
    void lockTimeoutDoesNotFallBackToDatabase() {
        assertFalse(service.queryList().getSuccess());
        verify(service, never()).list();
        verify(lock, never()).unlock();
    }

    @Test
    void writesCompleteListInOneCommandAndKeepsRandomTtl() throws Exception {
        ShopType first = new ShopType();
        first.setId(1L);
        first.setSort(1);
        ShopType second = new ShopType();
        second.setId(2L);
        second.setSort(2);
        doReturn(List.of(first, second)).when(service).list();
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertTrue(service.queryList().getSuccess());

        verify(zset).add(eq(CACHE_SHOP_TYPE_KEY), argThat(tuples -> tuples.size() == 2));
        verify(zset, never()).add(anyString(), anyString(), anyDouble());
        verify(redis).expire(eq(CACHE_SHOP_TYPE_KEY),
                longThat(ttl -> ttl >= 1800 && ttl <= 2100), eq(TimeUnit.SECONDS));
        verify(service, times(1)).list();
        verify(lock).unlock();
    }

    @Test
    void releasesLockWhenDatabaseFails() throws Exception {
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new IllegalStateException("database unavailable")).when(service).list();
        assertThrows(IllegalStateException.class, () -> service.queryList());
        verify(lock).unlock();
    }

    @Test
    void interruptionPreservesFlagWithoutQueryingDatabase() throws Exception {
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try {
            assertFalse(service.queryList().getSuccess());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(service, never()).list();
            verify(lock, never()).unlock();
        } finally {
            Thread.interrupted();
        }
    }
}
