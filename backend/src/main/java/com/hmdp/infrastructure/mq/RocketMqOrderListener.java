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

    /**
     * 处理整批消息，任何一条未完成则重试；已提交部分由业务幂等保护。
     *
     * @param messages 本轮消息，客户端配置每批一条
     * @param context  MQ 消费上下文
     * @return 全部提交后成功，否则稍后重试
     */
    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        // 遍历消息
        for (MessageExt message : messages) {
            // 如果当前线程被中断，则稍后重试
            if (Thread.currentThread().isInterrupted()) {
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
            try {
                // 如果消息标签或大小非法，则稍后重试
                if (!"CREATE_ORDER".equals(message.getTags()) || message.getBody() == null
                        || message.getBody().length > 65536) {
                    throw new IllegalArgumentException("建单消息标签或大小非法");
                }
                // 解析建单消息
                SeckillOrderMessage order = objectMapper.readValue(message.getBody(), SeckillOrderMessage.class);
                // 创建订单
                orders.createFromMessage(order);
            } catch (IOException | IllegalArgumentException ex) {
                // 暂不丢弃非法消息，超过重试上限进入 Broker 死信，隔离治理在第四阶段完善。
                log.warn("订单消息校验未通过，msgId={}，reconsumeTimes={}，errorType={}",
                        message.getMsgId(), message.getReconsumeTimes(), ex.getClass().getSimpleName());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } catch (RuntimeException ex) {
                log.debug("订单消息等待重试，msgId={}，reconsumeTimes={}，errorType={}",
                        message.getMsgId(), message.getReconsumeTimes(), ex.getClass().getSimpleName());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
