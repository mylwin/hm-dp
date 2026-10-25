package com.hmdp.infrastructure.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将当前订单消费组的死信持久化为待核对记录，绝不据此取消订单或回补库存。
 *
 * @author myl
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "hmdp", name = {"rocketmq.enabled", "rocketmq.consumer.enabled",
        "rocketmq.consumer.dead-letter-enabled", "seckill.reliable-components-enabled"}, havingValue = "true")
public class RocketMqDeadLetterListener implements MessageListenerConcurrently {
    private final OrderMessageFailureStore failures;

    /**
     * 把当前订单消费组的死信逐条保存为隔离待办，供后续人工核对、重投或处置。
     * 只在隔离数据库事务全部提交后向 Broker 确认，不因进入死信就关闭请求或回补库存。
     *
     * @param messages 本轮收到的死信消息列表，重复消息可按固定隔离编号再次保存
     * @param context  RocketMQ 消费上下文，本方法不修改消息或业务状态
     * @return 全批隔离已提交时为 CONSUME_SUCCESS；保存失败或记录前发现中断时为 RECONSUME_LATER
     */
    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        try {
            for (MessageExt message : messages) {
                failures.record(message, "DLQ", "BROKER_RETRY_EXHAUSTED");
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (RuntimeException ex) {
            log.warn("死信隔离未提交，保留 Broker 重试，errorType={}", ex.getClass().getSimpleName());
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }
}
