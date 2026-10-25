package com.hmdp.dto;

import com.hmdp.constants.SeckillRequestStatus;

/**
 * 当前用户的抢购结果，编号以字符串传输，避免浏览器丢失精度。
 *
 * @param orderId           稳定订单编号
 * @param requestId         客户端重试编号
 * @param voucherId         券编号
 * @param status            请求状态，不是支付状态
 * @param statusDescription 中文状态
 * @param reason            拒绝或关闭原因，正常处理中为 null
 * @param reasonDescription 中文原因，无原因时为 null
 */
public record SeckillRequestResult(String orderId, String requestId, String voucherId,
                                   SeckillRequestStatus status, String statusDescription,
                                   String reason, String reasonDescription) {
}
