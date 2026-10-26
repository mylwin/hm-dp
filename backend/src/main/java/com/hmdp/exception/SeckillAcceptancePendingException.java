package com.hmdp.exception;

import lombok.Getter;

/**
 * 受理执行结果不确定，必须沿用 requestId 查询或重试，不能直接回补库存。
 *
 * @author myl
 */
@Getter
public class SeckillAcceptancePendingException extends RuntimeException {
    private final Long orderId;

    /**
     * 保留可追踪的订单编号和原始故障。
     *
     * @param orderId 已提交 PREPARING 的订单编号
     * @param cause   Redis、数据库或提交异常
     */
    public SeckillAcceptancePendingException(Long orderId, Throwable cause) {
        super("秒杀受理结果待确认，请沿用原 requestId 查询或重试", cause);
        this.orderId = orderId;
    }
}
