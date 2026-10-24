package com.hmdp.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RocketMqProperties;
import com.hmdp.exception.OrderMessagePublishException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 只负责同步投递订单事件；可靠投递由后续阶段的 MySQL Outbox 保证。
 * @author myl
 */
@Component
@RequiredArgsConstructor
@Slf4j
// 只有在 RocketMQ 启用时才创建
@ConditionalOnProperty(prefix = "hmdp.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqOrderPublisher {
    /**
     * RocketMQ 生产者
     */
    private final DefaultMQProducer producer;
    /**
     * ObjectMapper 对象
     */
    private final ObjectMapper objectMapper;
    /**
     * RocketMQ 配置
     */
    private final RocketMqProperties properties;

    /**
     * 同步发布订单事件并记录发送结果；回执不表示订单已经创建。
     *
     * @param event 订单事件
     * @return 投递结果
     * @throws NullPointerException 订单事件为空时抛出
     * @throws OrderMessagePublishException 序列化失败、发送异常或未确认发送成功时抛出
     */
    public PublishReceipt publish(SeckillOrderMessage event) {
        Objects.requireNonNull(event, "订单事件不能为空");
        try {
            Message message = new Message(properties.getOrderTopic(), "CREATE_ORDER",
                    objectMapper.writeValueAsBytes(event));
            message.setKeys(List.of(event.eventId(), event.orderId().toString()));
            message.putUserProperty("traceId", event.traceId());
            SendResult result = producer.send(message, properties.getSendTimeout().toMillis());
            log.debug("订单消息发送回执，eventId={}，orderId={}，status={}，messageId={}",
                    event.eventId(), event.orderId(), result == null ? null : result.getSendStatus(),
                    result == null ? null : result.getMsgId());
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new OrderMessagePublishException("订单消息未确认成功，保留待发记录并重试；不能回补库存");
            }
            return new PublishReceipt(event.eventId(), result.getMsgId());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("订单消息发送中断，投递结果未知，eventId={}，orderId={}", event.eventId(), event.orderId());
            throw new OrderMessagePublishException("订单消息发送被中断，投递结果未知", ex);
        } catch (MQClientException | MQBrokerException | RemotingException ex) {
            log.debug("订单消息发送异常，未确认投递成功，eventId={}，orderId={}", event.eventId(), event.orderId(), ex);
            throw new OrderMessagePublishException("订单消息发送未确认成功，保留待发记录并重试", ex);
        } catch (JsonProcessingException ex) {
            throw new OrderMessagePublishException("订单消息序列化失败", ex);
        }
    }

    /**
     * Broker 收到消息的回执，不表示订单已经创建。
     *
     * @param eventId 订单事件ID
     * @param messageId 消息ID
     */
    public record PublishReceipt(String eventId, String messageId) {
    }
}
