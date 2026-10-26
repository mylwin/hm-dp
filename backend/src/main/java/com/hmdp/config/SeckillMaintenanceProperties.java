package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀补偿、恢复与运维开关；不切换旧 Stream 流量。
 *
 * @author myl
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.seckill.maintenance")
public class SeckillMaintenanceProperties {
    /**
     * 是否运行周期补偿、恢复和健康检查。
     */
    private boolean enabled;
    /**
     * 是否开放额外令牌保护的运维接口。
     */
    private boolean operationsEnabled;
    /**
     * 运维令牌，开启接口时至少 32 字符，不写入日志或响应。
     */
    private String operationsToken;
    /**
     * 每类任务单轮最多处理数量。
     */
    private int batchSize = 20;
    /**
     * 调度间隔，单位毫秒。
     */
    private long pollIntervalMs = 5000;
    /**
     * 请求无进展多久后检查，单位秒。
     */
    private int staleSeconds = 60;
    /**
     * 恢复尝试之间的最短间隔，单位秒。
     */
    private int recoveryRetrySeconds = 60;
    /**
     * 任务租约毫秒数。
     */
    private long leaseMs = 30000;
    /**
     * 最老待办超过此秒数时告警。
     */
    private int alertAgeSeconds = 120;
    /**
     * 单任务达到此尝试次数时告警，仍保留重试。
     */
    private int alertAttempts = 10;

    /**
     * 检查有界扫描、租约、告警和运维令牌配置。
     *
     * @throws IllegalArgumentException 配置超出允许范围或运维令牌过短
     */
    public void validate() {
        if (batchSize < 1 || batchSize > 100 || pollIntervalMs < 1000 || pollIntervalMs > 60000
                || staleSeconds < 5 || staleSeconds > 86400 || recoveryRetrySeconds < 5 || recoveryRetrySeconds > 86400
                || leaseMs < 1000 || leaseMs > 300000 || alertAgeSeconds < 5 || alertAgeSeconds > 86400
                || alertAttempts < 1 || alertAttempts > 10000) {
            throw new IllegalArgumentException("秒杀维护的批次、间隔、租约或告警配置越界");
        }
        if (operationsEnabled && (operationsToken == null || operationsToken.length() < 32)) {
            throw new IllegalArgumentException("秒杀运维接口必须配置至少 32 字符的独立令牌");
        }
    }
}
