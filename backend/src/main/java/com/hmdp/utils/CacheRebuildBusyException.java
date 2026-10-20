package com.hmdp.utils;

/** 缓存重建等待超时或被中断，不能据此判定数据不存在。 */
public class CacheRebuildBusyException extends RuntimeException {
    public CacheRebuildBusyException(String message) {
        super(message);
    }

    public CacheRebuildBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
