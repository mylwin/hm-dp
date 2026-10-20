package com.hmdp.utils;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.hmdp.utils.RedisConstants.LOGIN_JWT_KEY;

/**
 * JWT 登录会话的 Redis 存储。
 * 所有方法接收的 Claims 都必须先经过 JwtUtils.parseToken 验签。
 * 每个 jti 对应一个独立会话，Redis 中只保存用户 ID。
 */
@Component
@RequiredArgsConstructor
public class LoginTokenStore {
    private final StringRedisTemplate stringRedisTemplate;

    private final JwtUtils jwtUtils;

    /**
     * 登录成功时登记会话，TTL 对齐时登记会话，令牌剩余寿命，不做滑动续期。
     * 只在签发新令牌时调用，不能在鉴权请求中重复登记已撤销的会话。
     */
    public void register(Claims claims) {
        String jti = jwtUtils.getJti(claims);
        String userId = String.valueOf(jwtUtils.getUserId(claims));
        Duration ttl = jwtUtils.getRemainingTtl(claims);
        if (ttl.isZero()) {
            throw new IllegalStateException("JWT 令牌已过期");
        }
        stringRedisTemplate.opsForValue().set(LOGIN_JWT_KEY + jti, userId, ttl);
    }

    /**
     * 检查会话是否有效：令牌未过期，且白名单中的用户 ID 与声明一致。
     * 每次有效期内的检查只执行一次 GET，不续期、不重建缺失会话。
     * Redis 访问异常直接向调用方传播，不能当成鉴权成功。
     */
    public boolean isActive(Claims claims) {
        String jti = jwtUtils.getJti(claims);
        String userId = String.valueOf(jwtUtils.getUserId(claims));
        if (jwtUtils.getRemainingTtl(claims).isZero()) {
            return false;
        }
        return userId.equals(stringRedisTemplate.opsForValue().get(LOGIN_JWT_KEY + jti));
    }

    /**
     * 撤销当前会话。键不存在时仍视为已撤销，不影响同一用户的其他 jti。
     */
    public void revoke(Claims claims) {
        stringRedisTemplate.delete(LOGIN_JWT_KEY + jwtUtils.getJti(claims));
    }
}
