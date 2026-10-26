package com.hmdp.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

/**
 * 订单 MQ 接入配置；enabled 仅控制客户端，不切换秒杀业务通道。
 *
 * @author myl
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.rocketmq")
public class RocketMqProperties {
    /**
     * 是否开启 RocketMQ
     */
    private boolean enabled;
    /**
     * NameServer 地址
     */
    private String nameServer = "10.211.55.9:9876";
    /**
     * 生产者组
     */
    private String producerGroup = "hmdp-order-producer";
    /**
     * 订单 Topic
     */
    private String orderTopic = "hmdp-seckill-order-create";
    /**
     * 发送超时时间，支持 3s、1500ms 等 Duration 格式
     */
    private Duration sendTimeout = Duration.ofSeconds(3);
    /**
     * 应用层未来由 Outbox 管理重投，避免一次调用内叠加 SDK 重试。
     */
    private int sendRetries;
    /**
     * 是否开启 VIP 通道
     */
    private boolean vipChannelEnabled;
    /**
     * 访问密钥
     */
    @ToString.Exclude
    private String accessKey;
    /**
     * 秘密密钥
     */
    @ToString.Exclude
    private String secretKey;

    /**
     * 配置项校验，避免无效配置项导致初始化失败
     */
    public void validate() {
        if (!StringUtils.hasText(nameServer)) {
            throw new IllegalArgumentException("RocketMQ NameServer 不能为空");
        }
        for (String endpoint : nameServer.split(";", -1)) {
            URI address;
            try {
                address = URI.create("tcp://" + endpoint);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("RocketMQ NameServer 必须为 host:port，可用分号分隔", ex);
            }
            // 如果地址的主机名为空，或者端口为负数或大于 65535，或者用户名不为空，或者查询字符串不为空，或者片段标识符不为空，或者路径不为空，则抛出异常
            if (address.getHost() == null || address.getPort() < 1 || address.getPort() > 65535
                    || address.getUserInfo() != null || address.getQuery() != null
                    || address.getFragment() != null || !address.getPath().isEmpty()) {
                throw new IllegalArgumentException("RocketMQ NameServer 必须为 host:port，可用分号分隔");
            }
        }
        // 校验配置项的值是否符合规范
        validateName(producerGroup, "生产者组");
        validateName(orderTopic, "订单 Topic");
        if (sendTimeout == null || sendTimeout.compareTo(Duration.ofMillis(1)) < 0
                || sendTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("RocketMQ 发送超时必须为 1 到 2147483647 毫秒");
        }
        if (sendRetries < 0) {
            throw new IllegalArgumentException("RocketMQ SDK 重试次数不能为负数");
        }
        if (StringUtils.hasText(accessKey) != StringUtils.hasText(secretKey)) {
            throw new IllegalArgumentException("RocketMQ ACL access-key 与 secret-key 必须同时配置");
        }
    }

    /**
     * 校验配置项的值是否符合规范
     *
     * @param value 配置项值
     * @param label 配置项标签
     */
    private static void validateName(String value, String label) {
        // 如果值为空，或者值不符合正则表达式，则抛出异常
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,127}")) {
            throw new IllegalArgumentException("RocketMQ " + label + "必须为 1 到 127 位字母、数字、下划线或中划线");
        }
    }
}
