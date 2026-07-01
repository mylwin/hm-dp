package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson配置类
 * @author mylwin
 * @since 2026-07-01
 */
@Configuration
public class RedissonConfig {
    /**
     * 创建RedissonClient对象
     * @return RedissonClient对象
     */
    @Bean
    public RedissonClient redissonClient(){
        // 配置对象
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379").setPassword("20041223");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
