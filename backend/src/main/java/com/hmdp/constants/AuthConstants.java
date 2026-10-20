package com.hmdp.constants;

/** 认证、验证码及保留的旧会话常量，保留原有取值及时间单位。 */
public final class AuthConstants {
    private AuthConstants() {
    }

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

    /** 旧的单令牌白名单前缀，仅保留作迁移参考。 */
    @Deprecated
    public static final String LOGIN_JWT_KEY = "hmdp:login:jwt:";

    public static final String LOGIN_SESSION_KEY = "hmdp:login:session:";

    /**
     * session 中存储用户信息的键
     */
    public static final String USER_SESSION_KEY = "user";

    /**
     * session 中存储验证码的键
     */
    public static final String CODE_SESSION_KEY = "code";
}
