package com.hmdp.entity;

import lombok.Data;

/**
 * 持久化隔离消息；原文仅供受控核对，不通过查询接口返回。
 *
 * @author myl
 */
@Data
public class QuarantinedOrderMessage {
    private String quarantineId;
    private String source;
    private String messageId;
    private String topic;
    private byte[] body;
    private String bodyHash;
    private String errorType;
    private int reconsumeTimes;
    private boolean resolved;
}
