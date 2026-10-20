package com.hmdp.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 短信验证码的发送频控、失败锁定与一次性消费。
 */
@Component
@RequiredArgsConstructor
public class LoginCodeVerifier {
    private static final DefaultRedisScript<Long> SEND_SCRIPT =
            loadScript("login-code-send.lua");
    private static final DefaultRedisScript<Long> VERIFY_SCRIPT =
            loadScript("login-code-verify.lua");

    private final StringRedisTemplate stringRedisTemplate;

    private static DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 返回 null 表示已保存验证码，否则返回业务提示。
     */
    public String store(String phone, String code) {
        Long result = stringRedisTemplate.execute(SEND_SCRIPT,
                List.of(LOGIN_CODE_KEY + phone,
                        LOGIN_CODE_SEND_KEY + phone,
                        LOGIN_CODE_FAIL_KEY + phone),
                code, String.valueOf(LOGIN_CODE_TTL * 60), "60");
        if (Long.valueOf(0).equals(result)) return null;
        if (Long.valueOf(1).equals(result)) return "验证码发送过于频繁，请稍后再试";
        if (Long.valueOf(2).equals(result)) return "错误次数过多，请10分钟后重试";
        throw new IllegalStateException("验证码发送脚本返回异常");
    }

    /**
     * 校验通过后原子删除验证码，防止并发重放。
     */
    public String verify(String phone, String code) {
        Long result = stringRedisTemplate.execute(VERIFY_SCRIPT,
                List.of(LOGIN_CODE_KEY + phone,
                        LOGIN_CODE_FAIL_KEY + phone),
                code == null ? "" : code, "5", "600");
        if (Long.valueOf(0).equals(result)) return null;
        if (Long.valueOf(1).equals(result)) return "验证码错误或已过期";
        if (Long.valueOf(2).equals(result)) return "错误次数过多，请10分钟后重试";
        throw new IllegalStateException("验证码校验脚本返回异常");
    }
}
