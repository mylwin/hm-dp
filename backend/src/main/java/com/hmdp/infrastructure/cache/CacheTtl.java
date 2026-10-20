package com.hmdp.infrastructure.cache;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 为业务缓存增加随机过期偏移，分散集中重建压力。
 */
public final class CacheTtl {
    private CacheTtl() {
    }

    /**
     * 返回秒级 TTL，随机偏移范围为 [0, jitterSeconds]。
     * 仅用于业务缓存，不用于锁、验证码或登录令牌。
     *
     * @param time          时间
     * @param unit          时间单位
     * @param jitterSeconds 随机偏移秒数
     */
    public static long withJitter(long time, TimeUnit unit, long jitterSeconds) {
        return unit.toSeconds(time)
                + ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
    }
}
