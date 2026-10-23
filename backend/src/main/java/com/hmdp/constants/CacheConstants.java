package com.hmdp.constants;

/**
 * 业务缓存键、过期时间和随机偏移，保留原有取值及时间单位。
 *
 * @author myl
 */
public final class CacheConstants {
    private CacheConstants() {
    }

    public static final Long CACHE_NULL_TTL = 2L;

    /**
     * 空值缓存的最大随机偏移，单位为秒。
     */
    public static final long CACHE_NULL_TTL_JITTER_SECONDS = 30L;

    public static final Long CACHE_SHOP_TTL = 30L;

    /**
     * 业务数据缓存的最大随机偏移，单位为秒。
     */
    public static final long CACHE_TTL_JITTER_SECONDS = 300L;

    /**
     * 项目缓存命名空间，构建锁键时仅移除开头的这一段前缀。
     */
    public static final String CACHE_KEY_PREFIX = "hmdp:cache:";

    public static final String CACHE_SHOP_KEY = CACHE_KEY_PREFIX + "shop:";

    public static final String CACHE_SHOP_TYPE_KEY = CACHE_KEY_PREFIX + "shop:type:";
}
