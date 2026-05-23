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
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
