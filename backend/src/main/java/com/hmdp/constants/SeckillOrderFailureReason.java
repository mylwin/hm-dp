package com.hmdp.constants;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 已受理请求在数据库建单时被关闭的原因。
 *
 * @author myl
 */
@Getter
@RequiredArgsConstructor
public enum SeckillOrderFailureReason {
    ORDER_CONFLICT("已存在该优惠券的订单"),
    DB_STOCK_INSUFFICIENT("数据库库存不足"),
    OPERATOR_CLOSED("运维核对后关闭请求"),
    OPERATOR_CLOSED_PREPARING("运维核对后关闭待确认请求");

    private final String description;
}
