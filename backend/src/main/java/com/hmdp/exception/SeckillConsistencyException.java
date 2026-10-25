package com.hmdp.exception;

/**
 * 订单事实与预占不一致，需暂停活动并保留恢复依据。
 *
 * @author myl
 */
public class SeckillConsistencyException extends IllegalStateException {
    /**
     * 记录可用于排查的数据冲突说明，不包含凭据或完整消息。
     *
     * @param message 冲突说明
     */
    public SeckillConsistencyException(String message) {
        super(message);
    }
}
