package com.hmdp.constants;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 预占脚本返回码，异常或未知返回值不能解释为未扣库存。
 * 返回码显式对应 reserve-order.lua 的协议，不依赖枚举声明顺序。
 *
 * @author myl
 */
@Getter
@RequiredArgsConstructor
public enum SeckillReservationResult {
    /**
     * 首次预占成功，或同一订单已预占且归属一致；重试不重复扣库存。
     */
    RESERVED(0, "预占成功"),

    /**
     * 可用库存不足，本次请求已记录为拒绝。
     */
    SOLD_OUT(1, "库存不足"),

    /**
     * 同一用户已由其他订单占用购买资格，本次请求已记录为拒绝。
     */
    DUPLICATE_USER(2, "重复购买或预占"),

    /**
     * 数据缺失、类型异常、活动代次或预占归属不一致，需保留请求等待核对。
     */
    NOT_READY(3, "预占数据未就绪"),

    /**
     * 尚未到活动开始时间，本次请求已记录为拒绝。
     */
    NOT_STARTED(4, "活动未开始"),

    /**
     * 已到或超过活动结束时间，本次请求已记录为拒绝。
     */
    ENDED(5, "活动已结束"),

    /**
     * 预占已确认、已释放或已拒绝但原因码无法识别，禁止再次预占。
     */
    TERMINAL(6, "预占已处于终态"),

    /**
     * 同一订单的预占记录绑定了其他用户或优惠券，需核对标识冲突。
     */
    CONFLICT(7, "预占标识冲突");

    /**
     * Lua 预占脚本的固定返回码，调整时必须同步维护脚本协议。
     */
    private final int code;

    /**
     * 预占结果的中文说明，业务标识仍使用枚举名称。
     */
    private final String description;

    /**
     * 将脚本返回码转换为已知业务结果。
     *
     * @param code Lua 返回码，允许为 null
     * @return 对应的预占结果
     * @throws IllegalStateException 返回码缺失或不支持时抛出，需保留 PREPARING 等待恢复
     */
    public static SeckillReservationResult fromCode(Long code) {
        if (code != null) {
            for (SeckillReservationResult result : values()) {
                if (result.code == code) {
                    return result;
                }
            }
        }
        throw new IllegalStateException("Redis 预占结果未知");
    }
}
