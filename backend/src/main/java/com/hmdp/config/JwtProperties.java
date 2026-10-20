package com.hmdp.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * JWT配置类
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    /**
     * JWT的密钥
     */
    @ToString.Exclude
    private String secret;
    /**
     * JWT的签发者
     */
    private String issuer = "hm-dp";
    /**
     * JWT的过期时间
     */
    private Duration accessTtl = Duration.ofHours(12);
    /**
     * JWT的时间偏差
     */
    private long clockSkewSeconds = 60;
}
