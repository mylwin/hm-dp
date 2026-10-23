package com.hmdp.constants;

import cn.hutool.core.lang.UUID;

/**
 * 分布式锁与锁持有者标识常量，保留原有取值及时间单位。
 */
public final class LockConstants {
    private LockConstants() {
    }

    public static final String LOCK_SHOP_KEY = "hmdp:lock:shop:";

    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String LOCK_KEY = "hmdp:lock:";

    /** 缓存重建互斥锁前缀，与历史字符串锁使用不同命名空间。 */
    public static final String LOCK_CACHE_MUTEX_KEY = LOCK_KEY + "cache:mutex:";

    public static final Long LOCK_TTL = 10L;

    public static final String ID_PREFIX_KEY = UUID.randomUUID().toString(true) + "-";
}
