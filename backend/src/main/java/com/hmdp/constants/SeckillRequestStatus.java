package com.hmdp.constants;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 请求处理状态，与订单支付状态分开存储。
 * 枚举名称作为持久化和接口传输标识，中文说明用于展示和排查。
 *
 * @author myl
 */
@Getter
@RequiredArgsConstructor
public enum SeckillRequestStatus {
    /**
     * 请求账本已登记，预占和受理结果尚未确定，不能直接视为失败。
     */
    PREPARING("受理准备中"),

    /**
     * 预占成功，受理状态与待发消息已一起提交，尚不表示订单已创建。
     */
    ACCEPTED("已受理"),

    /**
     * 订单已成功创建；由后续订单消费阶段写入。
     */
    SUCCEEDED("建单成功"),

    /**
     * 已确认请求被业务规则拒绝，例如库存不足或重复购买。
     */
    REJECTED("已拒绝"),

    /**
     * 请求已关闭；由后续关闭流程写入，是否已补偿库存需另行核对。
     */
    CLOSED("已关闭");

    /**
     * 请求状态的中文说明，不替代枚举名称作为存储或传输标识。
     */
    private final String description;
}
