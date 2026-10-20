package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * Redis缓存工具类
 *
 * @author mylwin
 * @since 2026-06-19
 */
@Slf4j
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final long MUTEX_WAIT_SECONDS = 1L;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 把任意Java对象序列化为JSON并存储在Redis的string类型的key中，并且可以设置ttl过期时间
     *
     * @param key      缓存的key
     * @param value    缓存的值
     * @param time     过期时间
     * @param timeUnit 时间单位
     */
    public <T> void set(String key, T value, Long time, TimeUnit timeUnit) {
        long ttlSeconds = CacheTtl.withJitter(time, timeUnit, CACHE_TTL_JITTER_SECONDS);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 将key对应的缓存设置为空值
     * @param key 缓存的key
     */
    private void setNull(String key) {
        long ttlSeconds = CacheTtl.withJitter(CACHE_NULL_TTL, TimeUnit.MINUTES, CACHE_NULL_TTL_JITTER_SECONDS);
        stringRedisTemplate.opsForValue().set(key, "", ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 把任意Java对象序列化为JSON并存储在Redis的string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     *
     * @param key      缓存的key
     * @param value    缓存的值
     * @param time     过期时间
     * @param timeUnit 时间单位
     */
    public <T> void setWithLogicalExpire(String key, T value, Long time, TimeUnit timeUnit) {
        // 1.设置逻辑过期时间
        RedisData<T> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        // 2.写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     *
     * @param keyPrefix  缓存的key前缀
     * @param id         id
     * @param type       返回对象的类型
     * @param dbFallback 读数据库的函数
     * @param time       过期时间
     * @param timeUnit   时间单位
     * @return 查询结果
     */
    public <T, ID> T queryWithPassThrough(
            String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit timeUnit) {
        // 1.从redis查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.如果缓存存在，直接返回数据
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 2.1如果缓存为空值（""），返回错误信息
        if (json != null) return null;

        // 3.缓存不存在，查询数据库
        T dbData = dbFallback.apply(id);
        // 3.1如果数据库不存在，则写入空值到redis中
        if (dbData == null) {
            // 空值保留2分钟，并增加随机偏移
            this.setNull(key);
            return null;
        }
        // 3.2如果数据库存在，则写入redis
        this.set(key, dbData, time, timeUnit);
        return dbData;
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用互斥锁解决缓存击穿问题
     *
     * @param keyPrefix  缓存key的前缀
     * @param id         id
     * @param type       返回对象的类型
     * @param dbFallback 读数据库的函数
     * @param time       过期时间
     * @param timeUnit   时间单位
     * @return 查询结果
     */
    public <T, ID> T queryWithMutex(
            String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit timeUnit) {
        // 1.从 redis 查询商铺信息
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.存在，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 如果缓存中的是空值，直接返回错误状态（缓存穿透保护）
        if (json != null) {
            return null;
        }

        // 3.缓存未命中，尝试获取互斥锁重建缓存（缓存击穿保护）
        // 独立命名空间，避免与逻辑过期路径的旧字符串锁发生Redis类型冲突。
        RLock lock = redissonClient.getLock(LOCK_KEY + "cache:mutex:" + key);
        boolean locked = false;
        try {
            // 有限等待替代递归重试；不指定租期，使用Redisson看门狗续期。
            locked = lock.tryLock(MUTEX_WAIT_SECONDS, TimeUnit.SECONDS);
            // 无论是否获得锁，都检查等待期间其他请求是否已经完成重建。
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                // 其他线程已重建缓存，直接返回
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }
            if (!locked) {
                throw new CacheRebuildBusyException("系统繁忙，请稍后再试");
            }

            // 4.缓存确实不存在，查询数据库
            T dbData = dbFallback.apply(id);

            // 5.数据库中不存在，往redis中写入空并返回错误状态（直接写原生命令，避免二次序列化）
            if (dbData == null) {
                this.setNull(key);
                return null;
            }

            // 6.存在，将数据库信息写入redis，设置缓存时间
            this.set(key, dbData, time, timeUnit);
            return dbData;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CacheRebuildBusyException("操作被中断，请稍后再试", e);
        } finally {
            // 仅释放当前线程仍持有的锁，不再直接删除Redis锁Key。
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用逻辑过期解决缓存击穿问题
     *
     * @param keyPrefix  缓存key的前缀
     * @param id         id
     * @param type       返回对象的类型
     * @param dbFallback 读数据库的函数
     * @param time       过期时间
     * @param timeUnit   时间单位
     * @return 查询结果
     */
    public <T, ID> T queryWithLogicalExpire(
            String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit timeUnit
    ) {
        // 1.从 redis 查询商铺信息
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.缓存未命中，直接返回错误状态
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 3.缓存命中
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        T t = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 3.1缓存未过期，返回店铺信息
        if (expireTime.isAfter(LocalDateTime.now())) {
            return t;
        }

        // 4.缓存过期，尝试获取互斥锁
        String lockKey = LOCK_KEY + type.getSimpleName() + ":" + id;
        boolean isLocked = tryLock(lockKey);
        if (isLocked) {
            // 4.1获取锁之后再次检测缓存是否已被其他线程重建（双重检测）
            String doubleCheckJson = stringRedisTemplate.opsForValue().get(key);
            // 4.2缓存已被其他线程重建且未过期，释放锁并返回新数据
            if (StrUtil.isNotBlank(doubleCheckJson)) {
                RedisData doubleCheckData = JSONUtil.toBean(doubleCheckJson, RedisData.class);
                if (doubleCheckData.getExpireTime().isAfter(LocalDateTime.now())) {
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) doubleCheckData.getData(), type);
                }
            }
            // 4.2缓存确实需要重建，开启独立线程重建缓存，重建完成后释放锁
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    T dbData = dbFallback.apply(id);
                    // 将查询到的数据写入Redis
                    this.setWithLogicalExpire(key, dbData, time, timeUnit);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 5.返回旧的店铺信息（逻辑过期方案的核心：优先保证可用性）
        return t;
    }

    /**
     * 尝试获取锁
     *
     * @param key redis中锁的key
     * @return 是否获取成功
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "lock", LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key redis中锁的key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
