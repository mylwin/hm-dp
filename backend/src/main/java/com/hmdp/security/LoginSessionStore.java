package com.hmdp.security;

import com.hmdp.config.JwtProperties;
import com.hmdp.dto.LoginTokens;
import com.hmdp.dto.UserDTO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.hmdp.constants.AuthConstants.*;

/**
 * 七天会话存储。jti 区分每次签发，sid 关联同一端可撤销的登录会话。
 */
@Component
@RequiredArgsConstructor
public class LoginSessionStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> CREATE = script("lua/auth/login-session-create.lua", Long.class);
    private static final DefaultRedisScript<List> ROTATE = script("lua/auth/login-session-rotate.lua", List.class);
    private static final DefaultRedisScript<Long> REVOKE = script("lua/auth/login-session-revoke.lua", Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;

    /**
     * 加载 Lua 脚本。
     * @param path Lua 脚本文件路径
     * @param type 脚本返回值类型
     */
    private static <T> DefaultRedisScript<T> script(String path, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(type);
        return script;
    }

    /**
     * 原子创建登录会话，生成随机的会话 ID 与密钥。
     * @return 登录凭证
     */
    public LoginTokens create(UserDTO user) {
        String sid = UUID.randomUUID().toString().replace("-", "");
        String secret = newSecret();
        // Redis 与 Cookie 都按毫秒/秒保存时间，避免轮换前后出现纳秒精度差异。
        Instant deadline = Instant.ofEpochMilli(Instant.now().plus(jwtProperties.getRefreshTtl()).toEpochMilli());
        Long result = stringRedisTemplate.execute(CREATE, List.of(LOGIN_SESSION_KEY + sid),
                String.valueOf(user.getId()), hash(secret),
                user.getNickName() == null ? "" : user.getNickName(),
                user.getIcon() == null ? "" : user.getIcon(), String.valueOf(deadline.toEpochMilli()));
        if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("登录会话创建失败");
        return issue(user, sid, secret, deadline);
    }

    /**
     * 原子校验并轮换刷新凭证，拒绝旧凭证重放，不延长会话的绝对期限。
     * @param refreshToken 刷新凭证
     * @return 访问令牌
     */
    public LoginTokens refresh(String refreshToken) {
        String[] parts = split(refreshToken);
        String nextSecret = newSecret();
        List<?> session = stringRedisTemplate.execute(ROTATE, List.of(LOGIN_SESSION_KEY + parts[0]),
                hash(parts[1]), hash(nextSecret));
        if (session == null || session.size() != 4) throw new JwtException("登录已过期，请重新登录");
        UserDTO user = new UserDTO();
        user.setId(Long.valueOf(session.get(0).toString()));
        user.setNickName(session.get(1).toString());
        user.setIcon(session.get(2).toString());
        Instant deadline = Instant.ofEpochMilli(Long.parseLong(session.get(3).toString()));
        return issue(user, parts[0], nextSecret, deadline);
    }

    /**
     * 删除会话会使该端所有已签发 JWT 失效；有效请求只读一次 Redis。
     */
    public boolean isActive(Claims claims) {
        if (jwtTokenService.getRemainingTtl(claims).isZero()) return false;
        String uid = String.valueOf(jwtTokenService.getUserId(claims));
        Object stored = stringRedisTemplate.opsForHash()
                .get(LOGIN_SESSION_KEY + jwtTokenService.getSessionId(claims), "uid");
        return uid.equals(stored);
    }

    public void revoke(Claims claims) {
        stringRedisTemplate.delete(LOGIN_SESSION_KEY + jwtTokenService.getSessionId(claims));
    }

    /**
     * 访问令牌过期时，仍可凭刷新 Cookie 撤销会话。
     */
    public void revokeByRefresh(String refreshToken) {
        String[] parts = split(refreshToken);
        stringRedisTemplate.execute(REVOKE, List.of(LOGIN_SESSION_KEY + parts[0]), hash(parts[1]));
    }

    /**
     * 签发 JWT 与刷新凭证。
     * @param user 登录用户
     * @param sid 会话 ID
     * @param secret 会话密钥
     * @param deadline 绝对期限
     * @return 登录凭证
     */
    private LoginTokens issue(UserDTO user, String sid, String secret, Instant deadline) {
        // 签发访问令牌，有效期为 2 小时
        Instant accessDeadline = Instant.now().plus(jwtProperties.getAccessTtl());
        // 如果访问令牌的有效期超过会话的绝对期限，则将访问令牌的有效期设置为会话的绝对期限
        if (accessDeadline.isAfter(deadline)) accessDeadline = deadline;
        String jwt = jwtTokenService.generateToken(user.getId(), UUID.randomUUID().toString().replace("-", ""),
                user.getNickName(), user.getIcon(), sid, accessDeadline);
        return new LoginTokens(jwt, sid + "." + secret, deadline);
    }

    /**
     * 刷新凭证的格式为 sid.secret，用点号分隔。
     */
    private static String[] split(String value) {
        if (value == null || !value.matches("[0-9a-f]{32}\\.[A-Za-z0-9_-]{43}")) {
            throw new JwtException("刷新凭证无效");
        }
        return value.split("\\.", 2);
    }

    /**
     * 生成 32 字节随机字符串，Base64 URL 编码，无填充。
     */
    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 哈希，二进制转十六进制字符串。
     */
    private static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
