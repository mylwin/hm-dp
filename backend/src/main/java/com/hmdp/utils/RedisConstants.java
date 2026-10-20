package com.hmdp.utils;

import cn.hutool.core.lang.UUID;

/**
 * Redis 常量
 *
 * @author mylwin
 * @since 2026-05-23
 */
public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "hmdp:login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;

    /** 仅供保留的 RefreshInterceptor 使用，当前 JWT 链路不读取旧会话。 */
    @Deprecated
    public static final String LOGIN_USER_KEY = "hmdp:login:token:";
    /** 旧拦截器的滑动有效期，单位为秒。 */
    @Deprecated
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final String LOGIN_CODE_FAIL_KEY = "hmdp:login:code:fail:";
    public static final String LOGIN_CODE_SEND_KEY = "hmdp:login:code:send:";

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "hmdp:cache:shop:";

    public static final String CACHE_SHOP_TYPE_KEY = "hmdp:cache:shop:type:";

    public static final String LOCK_SHOP_KEY = "hmdp:lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String LOCK_KEY = "hmdp:lock:";
    public static final Long LOCK_TTL = 10L;

    public static final String ID_PREFIX_KEY = UUID.randomUUID().toString(true) + "-";

    public static final String SECKILL_STOCK_KEY = "hmdp:seckill:stock:";

    public static final String ORDER_MQ = "hmdp:stream:orders";

    public static final String BLOG_LIKED_KEY = "hmdp:blog:liked:";

    public static final String FOLLOW_USER_KEY = "hmdp:follow:user:";

    public static final String FEED_KEY = "hmdp:feed:";

    public static final String SHOP_GEO_KEY = "hmdp:shop:geo:";
    public static final String USER_SIGN_KEY = "hmdp:sign:";

    /** 旧的单令牌白名单前缀，仅保留作迁移参考。 */
    @Deprecated
    public static final String LOGIN_JWT_KEY = "hmdp:login:jwt:";
    public static final String LOGIN_SESSION_KEY = "hmdp:login:session:";
}
