package com.hmdp.infrastructure.redis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * redis全局唯一id生成器
 *
 * @author mylwin
 * @since 2026-06-20
 */
@Component
public class RedisIdGenerator {
    /**
     * 起始的时间戳（2022-09-01）
     */
    private static final long BEGIN_TIMESTAMP = 1661990400L;

    /**
     * 序列号的位数
     */
    private static final int ID_BITS = 32;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 全局唯一id生成器，基于redis的自增长功能，实现高并发下唯一id的生成
     *
     * @param keyPrefix key的前缀
     * @return 生成的唯一id
     */
    public long getNextId(String keyPrefix){
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSeconds - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1获取当前日期作为redis中的key后缀
        String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        String key = "hmdp:icr:" + keyPrefix + ":" + dateStr;
        // 2.2进行自增长
        long id = stringRedisTemplate.opsForValue().increment(key);

        // 3.拼接返回
        return timeStamp << ID_BITS | id;
    }
}