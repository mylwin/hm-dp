package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Outbox 扫描默认关闭；每次只领取一条，避免批量租约在排队发送时过期。
 *
 * @author myl
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.rocketmq.outbox")
public class OrderOutboxProperties {
    /**
     * 是否启用 Outbox 功能
     */
    private boolean enabled;
    /**
     * 每次扫描数量
     */
    private int batchSize = 20;
    /**
     * 每次扫描间隔
     */
    private long pollIntervalMs = 1000;
    /**
     * 单次租约
     */
    private long leaseMs = 30000;

    /**
     * 限制扫描量、扫描间隔和单次租约，避免任务占满数据库。
     *
     * @throws IllegalArgumentException 任意参数越界时抛出
     */
    public void validate() {
        if (batchSize < 1 || batchSize > 100 || pollIntervalMs < 100 || pollIntervalMs > 60000
                || leaseMs < 1000 || leaseMs > 300000) {
            throw new IllegalArgumentException("Outbox 批次为 1 到 100，间隔为 100 到 60000ms，租约为 1000 到 300000ms");
        }
    }
}
