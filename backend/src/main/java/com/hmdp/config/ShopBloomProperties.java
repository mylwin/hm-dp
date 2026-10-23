package com.hmdp.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 商铺布隆过滤器配置，用于构建、观察、新增同步和可选的负向拦截。
 *
 * @author myl
 */
@Getter
@Component
@ConfigurationProperties(prefix = "hmdp.cache.shop-bloom")
public class ShopBloomProperties {
    /**
     * 是否开启商铺布隆过滤器。
     */
    private boolean enabled = true;
    /**
     * 是否根据就绪快照的负向结果拦截缓存未命中请求，默认仅观察。
     */
    private boolean enforce = false;
    /**
     * 商铺布隆过滤器版本，用于构建快照和观察。
     */
    private String version = "v1";
    /**
     * 商铺布隆过滤器预期插入数量。
     */
    private long expectedInsertions = 100_000;
    /**
     * 商铺布隆过滤器误判率。
     */
    private double falseProbability = 0.001;
    /**
     * 商铺布隆过滤器批量处理大小。
     */
    private int batchSize = 500;

    /**
     * 设置是否开启商铺布隆过滤器。
     *
     * @param enabled 是否开启商铺布隆过滤器
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 设置是否启用负向拦截；启用前须保证所有新增入口同步布隆过滤器。
     *
     * @param enforce true 表示允许拦截就绪快照明确不包含的 ID，false 表示仅观察
     */
    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }

    /**
     * 设置商铺布隆过滤器版本。
     *
     * @param version 商铺布隆过滤器版本
     */
    public void setVersion(String version) {
        if (version == null || !version.matches("[a-zA-Z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("商铺布隆过滤器版本只允许1至32位字母、数字、下划线和短横线");
        }
        this.version = version;
    }

    /**
     * 设置商铺布隆过滤器预期插入数量。
     *
     * @param expectedInsertions 商铺布隆过滤器预期插入数量
     */
    public void setExpectedInsertions(long expectedInsertions) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("商铺布隆过滤器预期容量必须大于0");
        }
        this.expectedInsertions = expectedInsertions;
    }

    /**
     * 设置商铺布隆过滤器误判率。
     *
     * @param falseProbability 商铺布隆过滤器误判率
     */
    public void setFalseProbability(double falseProbability) {
        if (!Double.isFinite(falseProbability) || falseProbability <= 0 || falseProbability >= 1) {
            throw new IllegalArgumentException("商铺布隆过滤器误判率必须在0和1之间");
        }
        this.falseProbability = falseProbability;
    }

    /**
     * 设置商铺布隆过滤器批量处理大小。
     *
     * @param batchSize 商铺布隆过滤器批量处理大小
     */
    public void setBatchSize(int batchSize) {
        if (batchSize <= 0 || batchSize > 10_000) {
            throw new IllegalArgumentException("商铺布隆过滤器批量大小必须在1至10000之间");
        }
        this.batchSize = batchSize;
    }
}
