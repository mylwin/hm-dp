package com.hmdp.exception;

import com.hmdp.constants.SeckillOrderFailureReason;
import lombok.Getter;

/**
 * 使建单事务回滚，再另开事务关闭请求。
 *
 * @author myl
 */
@Getter
public class OrderCreationRejectedException extends RuntimeException {
    private final SeckillOrderFailureReason reason;

    /**
     * 保存已确认的业务失败原因。
     *
     * @param reason 库存不足或已有其他订单
     */
    public OrderCreationRejectedException(SeckillOrderFailureReason reason) {
        super(reason.getDescription());
        this.reason = reason;
    }
}
