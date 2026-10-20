package com.hmdp.constants;


/**
 * Redis 常量
 *
 * 保留旧聚合常量作为学习参考，新代码使用按功能拆分的常量类。
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Deprecated
public class RedisConstants {
    public static final String LOGIN_CODE_KEY = AuthConstants.LOGIN_CODE_KEY;
    public static final Long LOGIN_CODE_TTL = AuthConstants.LOGIN_CODE_TTL;

    /** 仅供保留的 RefreshInterceptor 使用，当前 JWT 链路不读取旧会话。 */
    @Deprecated
    public static final String LOGIN_USER_KEY = AuthConstants.LOGIN_USER_KEY;
    /** 旧拦截器的滑动有效期，单位为秒。 */
    @Deprecated
    public static final Long LOGIN_USER_TTL = AuthConstants.LOGIN_USER_TTL;

    public static final String LOGIN_CODE_FAIL_KEY = AuthConstants.LOGIN_CODE_FAIL_KEY;
    public static final String LOGIN_CODE_SEND_KEY = AuthConstants.LOGIN_CODE_SEND_KEY;

    public static final Long CACHE_NULL_TTL = CacheConstants.CACHE_NULL_TTL;
    /** 空值缓存的最大随机偏移，单位为秒。 */
    public static final long CACHE_NULL_TTL_JITTER_SECONDS = CacheConstants.CACHE_NULL_TTL_JITTER_SECONDS;

    public static final Long CACHE_SHOP_TTL = CacheConstants.CACHE_SHOP_TTL;
    /** 业务数据缓存的最大随机偏移，单位为秒。 */
    public static final long CACHE_TTL_JITTER_SECONDS = CacheConstants.CACHE_TTL_JITTER_SECONDS;
    public static final String CACHE_SHOP_KEY = CacheConstants.CACHE_SHOP_KEY;

    public static final String CACHE_SHOP_TYPE_KEY = CacheConstants.CACHE_SHOP_TYPE_KEY;

    public static final String LOCK_SHOP_KEY = LockConstants.LOCK_SHOP_KEY;
    public static final Long LOCK_SHOP_TTL = LockConstants.LOCK_SHOP_TTL;

    public static final String LOCK_KEY = LockConstants.LOCK_KEY;
    public static final Long LOCK_TTL = LockConstants.LOCK_TTL;

    public static final String ID_PREFIX_KEY = LockConstants.ID_PREFIX_KEY;

    public static final String SECKILL_STOCK_KEY = SeckillConstants.SECKILL_STOCK_KEY;

    public static final String ORDER_MQ = SeckillConstants.ORDER_MQ;

    public static final String BLOG_LIKED_KEY = BlogConstants.BLOG_LIKED_KEY;

    public static final String FOLLOW_USER_KEY = FollowConstants.FOLLOW_USER_KEY;

    public static final String FEED_KEY = BlogConstants.FEED_KEY;

    public static final String SHOP_GEO_KEY = ShopConstants.SHOP_GEO_KEY;
    public static final String USER_SIGN_KEY = UserConstants.USER_SIGN_KEY;

    /** 旧的单令牌白名单前缀，仅保留作迁移参考。 */
    @Deprecated
    public static final String LOGIN_JWT_KEY = AuthConstants.LOGIN_JWT_KEY;
    public static final String LOGIN_SESSION_KEY = AuthConstants.LOGIN_SESSION_KEY;
}
