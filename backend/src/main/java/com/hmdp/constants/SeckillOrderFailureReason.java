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
    DB_STOCK_INSUFFICIENT("数据库库存不足");

    private final String description;
}
