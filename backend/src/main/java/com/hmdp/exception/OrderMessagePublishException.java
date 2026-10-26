package com.hmdp.exception;

/**
 * 发送没有得到成功确认；调用方应保留待发记录，不能据此回补库存。
 *
 * @author myl
 */
public class OrderMessagePublishException extends RuntimeException {
    /**
     * 订单消息发布异常
     *
     * @param message 异常信息
     */
    public OrderMessagePublishException(String message) {
        super(message);
    }

    /**
     * 订单消息发布异常
     *
     * @param message 异常信息
     * @param cause   异常原因
     */
    public OrderMessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
