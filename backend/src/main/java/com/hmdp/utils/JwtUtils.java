package com.hmdp.utils;

import com.hmdp.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 的签发与本地校验。Redis 会话登记、撤销由独立服务负责。
 * 通过 Spring 注入使用，密钥按 UTF-8 文本读取，不做 Base64 解码。
 */
@Component
@RequiredArgsConstructor
public class JwtUtils {
    /**
     * jwt 相关的配置
     */
    private final JwtProperties jwtProperties;

    /**
     * 签名密钥
     */
    private SecretKey key;
    /**
     * JWT 解析器
     */
    private JwtParser parser;

    /**
     * 初始化密钥和解析器
     */
    @PostConstruct
    void init() {
        String secret = jwtProperties.getSecret();
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("Jwt Secret 未配置");
        }
        if (!StringUtils.hasText(jwtProperties.getIssuer())) {
            throw new IllegalStateException("Jwt issuer 未配置");
        }
        Duration ttl = jwtProperties.getAccessTtl();
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalStateException("Jwt access-ttl 必须至少为 1 秒");
        }
        if (jwtProperties.getClockSkewSeconds() < 0) {
            throw new IllegalStateException("Jwt clock-skew-seconds 不能为负数");
        }
        // 密钥按 UTF-8 文本读取，不做 Base64 解码。
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        // 解析器配置，按需添加签名算法，指定签发者，设置时钟偏差容忍度。
        this.parser = Jwts.parser()
                .verifyWith(key)
                .sig().clear().add(Jwts.SIG.HS256).and()
                .requireIssuer(jwtProperties.getIssuer())
                .clockSkewSeconds(jwtProperties.getClockSkewSeconds())
                .build();
    }

    /**
     * 只写入身份和会话声明，不在令牌中存储手机号、密码等敏感信息。
     *
     * @param userId 用户 ID
     * @param jti    会话 ID，必须非空
     * @return JWT 令牌
     */
    public String generateToken(Long userId, String jti) {
        return generateToken(userId, jti, null, null);
    }

    /** 附带非敏感资料快照，保持 /user/me 的昵称与头像返回契约。 */
    public String generateToken(Long userId, String jti, String nickName, String icon) {
        if (userId == null || userId <= 0 || !StringUtils.hasText(jti)) {
            throw new IllegalArgumentException("userId 必须为正数，jti 不能为空");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(String.valueOf(userId))
                .id(jti)
                .claim("nickName", nickName)
                .claim("icon", icon)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.getAccessTtl())))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 校验签名、算法、签发者及有效期，再返回可信声明。
     * 无效令牌抛出 JwtException，由调用方映射为 HTTP 401。
     * 通过本地校验后，调用方仍须检查 Redis 白名单以确认会话未撤销。
     *
     * @param token JWT 令牌
     * @return 可信的声明
     */
    public Claims parseToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new MalformedJwtException("JWT 不能为空");
        }
        Claims claims = parser.parseSignedClaims(token).getPayload();
        getUserId(claims);
        getJti(claims);
        if (claims.getExpiration() == null) {
            throw new MalformedJwtException("JWT 缺少 exp");
        }
        return claims;
    }

    /**
     * 仅对已经由 parseToken 校验过的声明调用。
     */
    public Long getUserId(Claims claims) {
        try {
            long userId = Long.parseLong(claims.getSubject());
            if (userId > 0) {
                return userId;
            }
        } catch (NumberFormatException ignored) {
            // 将缺失、非数字或溢出的 sub 统一按非法令牌处理。
        }
        throw new MalformedJwtException("JWT sub 必须为有效用户 ID");
    }

    /**
     * 仅对已经由 parseToken 校验过的声明调用。
     */
    public String getJti(Claims claims) {
        if (!StringUtils.hasText(claims.getId())) {
            throw new MalformedJwtException("JWT 缺少 jti");
        }
        return claims.getId();
    }

    /**
     * Redis 白名单 TTL 对齐 exp，不能借此延长 JWT 的有效期。
     */
    public Duration getRemainingTtl(Claims claims) {
        if (claims.getExpiration() == null) {
            throw new MalformedJwtException("JWT 缺少 exp");
        }
        Duration remaining = Duration.between(Instant.now(), claims.getExpiration().toInstant());
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** 同时兼容现有前端裸串和标准 Bearer 请求头。 */
    public static String stripBearer(String header) {
        if (!StringUtils.hasText(header)) {
            return "";
        }
        String token = header.trim();
        return token.regionMatches(true, 0, "Bearer ", 0, 7)
                ? token.substring(7).trim() : token;
    }
}
