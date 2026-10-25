package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 订单消费者的启用开关、消费组和重试配置。
 *
 * @author myl
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.rocketmq.consumer")
public class OrderConsumerProperties {
    /**
     * 是否启用订单消费者。
     */
    private boolean enabled;
    /**
     * 消费组。
     */
    private String group = "hmdp-seckill-order-create-cg";
    /**
     * 消费线程数。
     */
    private int threads = 4;
    /**
     * 消费者重试次数。
     */
    private int maxReconsumeTimes = 16;
    /**
     * 是否订阅本订单消费组的死信并持久化隔离。
     */
    private boolean deadLetterEnabled;
    /**
     * 死信观察消费者的独立组，不能与原建单组相同。
     */
    private String deadLetterGroup = "hmdp-seckill-order-dlq-cg";

    /**
     * 检查消费组、线程数和重试次数。
     *
     * @throws IllegalArgumentException 配置超出允许范围
     */
    public void validate() {
        if (group == null || !group.matches("[A-Za-z0-9_-]{1,127}")) {
            throw new IllegalArgumentException("订单消费组必须为 1 到 127 位字母、数字、下划线或中划线");
        }
        if (threads < 1 || threads > 32 || maxReconsumeTimes < 1 || maxReconsumeTimes > 64) {
            throw new IllegalArgumentException("消费线程数应为 1 到 32，重试次数应为 1 到 64");
        }
        if (deadLetterEnabled && (deadLetterGroup == null || !deadLetterGroup.matches("[A-Za-z0-9_-]{1,127}")
                || deadLetterGroup.equals(group))) {
            throw new IllegalArgumentException("死信消费组必须合法且不同于建单消费组");
        }
    }
}
