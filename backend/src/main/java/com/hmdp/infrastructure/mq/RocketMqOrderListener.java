package com.hmdp.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.ISeckillOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 解析建单消息，业务事务提交后才向 RocketMQ 确认消费。
 *
 * @author myl
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "hmdp", name = {"rocketmq.enabled", "rocketmq.consumer.enabled",
        "seckill.reliable-components-enabled"}, havingValue = "true")
public class RocketMqOrderListener implements MessageListenerConcurrently {
    private final ObjectMapper objectMapper;
    private final ISeckillOrderService orders;
    private final OrderMessageFailureStore failures;

    /**
     * 逐条校验建单消息：合法消息交给原幂等建单事务，非法协议消息先保存隔离记录。
     * 订单业务事务或隔离事务返回后才视为当前消息处理完成；业务异常、隔离失败或线程中断均要求 Broker 重试。
     * 已完成部分可被再次投递，由原订单号及隔离编号保证不会重复建单或丢失排查记录。
     *
     * @param messages 当前消费批次，客户端配置每批一条；方法仍逐条检查整个列表
     * @param context RocketMQ 消费上下文，本方法不修改重试策略
     * @return 全批已完成建单或可靠隔离时为 CONSUME_SUCCESS；任一条未确认完成时为 RECONSUME_LATER
     */
    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        // 遍历消息
        for (MessageExt message : messages) {
            // 如果当前线程被中断，则稍后重试
            if (Thread.currentThread().isInterrupted()) {
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
            SeckillOrderMessage order;
            try {
                // 标签或大小错误先交下方隔离；隔离提交后可确认，隔离失败才要求 Broker 重试。
                if (!"CREATE_ORDER".equals(message.getTags()) || message.getBody() == null
                        || message.getBody().length > 65536) {
                    throw new IllegalArgumentException("建单消息标签或大小非法");
                }
                // 解析建单消息
                order = objectMapper.readValue(message.getBody(), SeckillOrderMessage.class);
                if (order == null) {
                    throw new IllegalArgumentException("订单消息不能为空");
                }
            } catch (IOException | IllegalArgumentException ex) {
                // 非法协议消息提交隔离记录后可确认，不让毒消息无限重试；存储失败则重试。
                try {
                    failures.record(message, "INVALID", ex.getClass().getSimpleName());
                    continue;
                } catch (RuntimeException storageFailure) {
                    log.warn("非法消息隔离未提交，msgId={} errorType={}", message.getMsgId(), storageFailure.getClass().getSimpleName());
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            try {
                // 业务身份冲突不当作解析错误确认，继续交 Broker 重试及死信隔离。
                orders.createFromMessage(order);
            } catch (RuntimeException ex) {
                log.warn("SECKILL_CONSUME_RETRY msgId={} orderId={} reconsumeTimes={} errorType={}",
                        message.getMsgId(), order.orderId(), message.getReconsumeTimes(), ex.getClass().getSimpleName());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
