package com.hmdp.entity;

import com.hmdp.constants.SeckillRequestStatus;
import lombok.Data;

import java.time.Instant;

/**
 * 秒杀受理账本；数据库是请求状态的事实来源。
 * @author myl
 */
@Data
public class SeckillRequest {
    /**
     * 订单ID
     */
    private Long orderId;
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 券ID
     */
    private Long voucherId;
    /**
     * 请求ID
     */
    private String requestId;
    /**
     * 请求负载的哈希值
     */
    private String payloadHash;
    /**
     * 跟踪ID
     */
    private String traceId;
    /**
     * 请求状态
     */
    private SeckillRequestStatus status;
    /**
     * 失败原因
     */
    private String failureReason;
    /**
     * 预约时间
     */
    private Long reservationEpoch;
    /**
     * 接受时间
     */
    private Instant acceptedAt;
    /**
     * 创建时间
     */
    private Instant createdAt;
    /**
     * 更新时间
     */
    private Instant updatedAt;
}
