package com.hmdp.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RocketMqConfiguration;
import com.hmdp.config.RocketMqProperties;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式运行的真实 MQ 收发测试，不启动应用、MySQL、Redis 或业务消费者。 */
@EnabledIfEnvironmentVariable(named = "ROCKETMQ_SMOKE_TEST", matches = "true")
class RocketMqSmokeIT {
    @Test
    void sendsAndConsumesIsolatedProbeMessage() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String topic = System.getenv().getOrDefault("ROCKETMQ_SMOKE_TOPIC", "hmdp-order-smoke");
        assertThat(topic).startsWith("hmdp-order-smoke");
        RocketMqProperties properties = new RocketMqProperties();
        properties.setNameServer(System.getenv().getOrDefault("ROCKETMQ_NAMESERVER", "10.211.55.9:9876"));
        properties.setOrderTopic(topic);
        properties.setProducerGroup("hmdp-smoke-producer-" + suffix);
        properties.setAccessKey(System.getenv("ROCKETMQ_ACCESS_KEY"));
        properties.setSecretKey(System.getenv("ROCKETMQ_SECRET_KEY"));
        DefaultMQProducer producer = new RocketMqConfiguration(properties).rocketMqProducer();
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("hmdp-smoke-consumer-" + suffix,
                StringUtils.hasText(properties.getAccessKey())
                        ? new AclClientRPCHook(new SessionCredentials(properties.getAccessKey(), properties.getSecretKey()))
                        : null);
        consumer.setNamesrvAddr(properties.getNameServer());
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SeckillOrderMessage event = new SeckillOrderMessage(1, "smoke-" + suffix, 1L, 1L, 1L,
                "smoke-" + suffix, Instant.now());
        byte[] expectedBody = mapper.writeValueAsBytes(event);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<MessageExt> captured = new AtomicReference<>();
        consumer.subscribe(topic, "CREATE_ORDER");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                if (message.getKeys() != null && message.getKeys().contains(event.eventId())) {
                    captured.set(message);
                    received.countDown();
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        try {
            producer.start();
            // 先发探针，兼容尚未预建且允许自动创建 Topic 的开发 Broker。
            RocketMqOrderPublisher.PublishReceipt receipt =
                    new RocketMqOrderPublisher(producer, mapper, properties).publish(event);
            consumer.start();
            assertThat(received.await(30, TimeUnit.SECONDS)).as("独立测试 Topic 应在 30 秒内收到当前探针").isTrue();
            assertThat(captured.get().getBody()).isEqualTo(expectedBody);
            assertThat(captured.get().getUserProperty("traceId")).isEqualTo(event.traceId());
            System.out.printf("RocketMQ smoke passed: nameserver=%s, topic=%s, messageId=%s%n",
                    properties.getNameServer(), topic, receipt.messageId());
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }
}
