package com.hmdp.infrastructure.mq;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Objects;

/**
 * 稳定业务标识由受理方提供；重投不得重新生成 eventId 或 orderId。
 * @param schemaVersion 消息模式版本号
 * @param eventId       稳定业务标识
 * @param orderId       订单编号
 * @param userId        用户编号
 * @param voucherId     优惠券编号
 * @param traceId       跟踪标识
 * @param acceptedAt    受理时间
 */
public record SeckillOrderMessage(int schemaVersion, String eventId, Long orderId,
                                  Long userId, Long voucherId, String traceId, Instant acceptedAt) {
    /**
     * 当前消息模式版本号
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 检查订单消息的参数
     */
    public SeckillOrderMessage {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的订单消息版本");
        }
        if (!StringUtils.hasText(eventId) || !eventId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("订单 eventId 必须为 1 到 128 位字母、数字、下划线或中划线");
        }
        if (orderId == null || orderId <= 0 || userId == null || userId <= 0
                || voucherId == null || voucherId <= 0) {
            throw new IllegalArgumentException("订单、用户、优惠券编号必须为正数");
        }
        if (!StringUtils.hasText(traceId) || traceId.length() > 128) {
            throw new IllegalArgumentException("订单 traceId 不能为空且最多 128 个字符");
        }
        Objects.requireNonNull(acceptedAt, "订单受理时间不能为空");
    }
}
