package com.hmdp.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务缓存回源配置，启动时绑定，每个后端实例独立生效。
 * @author myl
 */
@Getter
@Component
@ConfigurationProperties(prefix = "hmdp.cache")
public class CacheProperties {
    private int maxConcurrentFallbacks = 10;

    /**
     * 设置缓存回源并发上限
     *
     * @param maxConcurrentFallbacks 缓存回源并发上限
     */
    public void setMaxConcurrentFallbacks(int maxConcurrentFallbacks) {
        if (maxConcurrentFallbacks <= 0) {
            throw new IllegalArgumentException("缓存回源并发上限必须大于0");
        }
        this.maxConcurrentFallbacks = maxConcurrentFallbacks;
    }
}
