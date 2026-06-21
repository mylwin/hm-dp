package com.hmdp.utils;

import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.ID_PREFIX_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_KEY;

/**
 * 简单的Redis锁实现
 */
@AllArgsConstructor
public class SimpleRedisLock implements ILock{
    /**
     * 当前业务的名称，用于生成锁的key
     */
    private String name;

    /**
     * redis操作工具
     */
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试获取锁
     * @param timeoutSec 锁的过期时间，单位为秒
     * @return 是否获取成功，true表示获取成功，false表示获取失败
     */
    @Override
    public boolean tryLock(Long timeoutSec) {
        // 获取当前线程ID
        long threadID = Thread.currentThread().getId();
        // 拼接得到锁的内容
        String value = ID_PREFIX_KEY + threadID;
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY + name, value, timeoutSec, TimeUnit.SECONDS);
        // 防止拆箱过程出现空指针
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        // 得到锁的值
        String value = stringRedisTemplate.opsForValue().get(LOCK_KEY + name);
        // 得到当前线程对应锁的值
        String currentThreadValue = ID_PREFIX_KEY + Thread.currentThread().getId();
        // 判断锁的值是否等于当前线程的值
        if (value.equals(currentThreadValue)) {
            // 释放锁
            stringRedisTemplate.delete(LOCK_KEY + name);
        }
    }
}
