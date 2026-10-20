package com.hmdp.constants;

/** 业务缓存键、过期时间和随机偏移，保留原有取值及时间单位。 */
public final class CacheConstants {
    private CacheConstants() {
    }

    public static final Long CACHE_NULL_TTL = 2L;

    /** 空值缓存的最大随机偏移，单位为秒。 */
    public static final long CACHE_NULL_TTL_JITTER_SECONDS = 30L;

    public static final Long CACHE_SHOP_TTL = 30L;

    /** 业务数据缓存的最大随机偏移，单位为秒。 */
    public static final long CACHE_TTL_JITTER_SECONDS = 300L;

    public static final String CACHE_SHOP_KEY = "hmdp:cache:shop:";

    public static final String CACHE_SHOP_TYPE_KEY = "hmdp:cache:shop:type:";
}
