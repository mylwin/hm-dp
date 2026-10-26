package com.hmdp.dto;

import com.hmdp.constants.SeckillRequestStatus;

/**
 * 新链路受理结果，不表示支付状态。
 * @param orderId 稳定订单编号
 * @param status 请求状态，ACCEPTED 表示消息已登记但尚未建单
 * @param reason 已知拒绝原因，未拒绝时为 null
 */
public record SeckillAcceptance(Long orderId, SeckillRequestStatus status, String reason) {
}
