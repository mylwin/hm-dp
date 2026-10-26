package com.hmdp.entity;

import lombok.Data;

import java.time.Instant;

/**
 * 持久化待发消息，发送重试复用原消息体与事件编号。
 *
 * @author myl
 */
@Data
public class MqOutbox {
    /**
     * 消息ID
     */
    private String eventId;
    /**
     * 订单ID
     */
    private Long orderId;
    /**
     * 事件类型
     */
    private String eventType;
    /**
     * 消息版本
     */
    private Integer schemaVersion;
    /**
     * 消息负载
     */
    private String payload;
    /**
     * 消息状态
     */
    private String status;
    /**
     * 尝试次数
     */
    private Integer attempts;
    /**
     * 下次重试时间
     */
    private Instant nextRetryAt;
    /**
     * 租约令牌
     */
    private String leaseToken;
    /**
     * 租约截止时间
     */
    private Instant leaseUntil;
    /**
     * 代理消息ID
     */
    private String brokerMessageId;
    /**
     * 最后错误
     */
    private String lastError;
}
