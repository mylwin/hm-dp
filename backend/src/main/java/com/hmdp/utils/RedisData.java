package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Redis 数据封装
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Data
public class RedisData<T> {
    private LocalDateTime expireTime;
    private T data;
}
