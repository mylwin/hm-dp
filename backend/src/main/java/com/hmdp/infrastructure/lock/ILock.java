package com.hmdp.infrastructure.lock;

/**
 * 锁的接口
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 锁的过期时间
     * @return 是否获取成功，true表示获取成功，false表示获取失败
     */
    boolean tryLock(Long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
