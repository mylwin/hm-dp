package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson配置类
 * @author mylwin
 * @since 2026-07-01
 */
@Configuration
public class RedissonConfig {
    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * 创建RedissonClient对象
     * @return RedissonClient对象
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(){
        // 配置对象
        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);
        if (StringUtils.hasText(redisPassword)) {
            serverConfig.setPassword(redisPassword);
        }
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
