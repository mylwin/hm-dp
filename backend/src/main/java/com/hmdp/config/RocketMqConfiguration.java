package com.hmdp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.hmdp.infrastructure.mq.RocketMqOrderListener;
import com.hmdp.service.ISeckillOrderService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.remoting.RPCHook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 管理 RocketMQ 客户端生命周期；新订单消费者受独立开关控制。
 *
 * @author myl
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@Slf4j
public class RocketMqConfiguration {
    private final RocketMqProperties properties;

    /**
     * 创建并配置生产者，启动和关闭由 Spring Bean 生命周期管理。
     *
     * @return 生产者
     * @throws IllegalArgumentException 配置参数不合法时抛出
     */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    // 只有当配置文件中 hmdp.rocketmq.enabled 属性为 true 时，才创建并初始化生产者
    @ConditionalOnProperty(prefix = "hmdp.rocketmq", name = "enabled", havingValue = "true")
    public DefaultMQProducer rocketMqProducer() {
        // 验证配置属性
        properties.validate();
        // 配置属性是否有访问密钥，如果有则使用 ACL 访问，否则不使用 ACL 访问
        RPCHook hook = StringUtils.hasText(properties.getAccessKey())
                ? new AclClientRPCHook(new SessionCredentials(properties.getAccessKey(), properties.getSecretKey()))
                : null;
        // 创建生产者
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup(), hook);
        // 设置生产者相关属性
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setSendMsgTimeout(Math.toIntExact(properties.getSendTimeout().toMillis()));
        producer.setRetryTimesWhenSendFailed(properties.getSendRetries());
        producer.setRetryTimesWhenSendAsyncFailed(properties.getSendRetries());
        producer.setVipChannelEnabled(properties.isVipChannelEnabled());
        log.debug("RocketMQ 生产者配置完成，nameServer={}，producerGroup={}，topic={}，aclEnabled={}",
                properties.getNameServer(), properties.getProducerGroup(), properties.getOrderTopic(), hook != null);
        return producer;
    }

    /**
     * 创建集群订单消费者，确认唯一索引后才启动订阅。
     * @param consumerProperties 消费配置
     * @param listener 订单消息监听器
     * @param orders 新链路建单服务
     * @return 由 Spring 启停的消费者
     * @throws MQClientException 订阅配置失败
     * @throws IllegalStateException 订单唯一索引缺失
     */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "hmdp", name = {"rocketmq.enabled", "rocketmq.consumer.enabled",
            "seckill.reliable-components-enabled"}, havingValue = "true")
    public DefaultMQPushConsumer rocketMqOrderConsumer(OrderConsumerProperties consumerProperties,
                                                       RocketMqOrderListener listener,
                                                       ISeckillOrderService orders) throws MQClientException {
        // 验证rocketmq配置属性
        properties.validate();
        // 验证消费者配置属性
        consumerProperties.validate();
        // 验证订单唯一索引
        orders.verifySchema();
        // 配置属性是否有访问密钥，如果有则使用 ACL 访问，否则不使用 ACL 访问
        RPCHook hook = StringUtils.hasText(properties.getAccessKey())
                ? new AclClientRPCHook(new SessionCredentials(properties.getAccessKey(), properties.getSecretKey()))
                : null;
        // 创建消费者
        DefaultMQPushConsumer consumer = createConsumer(consumerProperties, hook);
        // 注册消息监听器
        consumer.registerMessageListener(listener);
        log.debug("RocketMQ 订单消费者配置完成，group={}，topic={}",
                consumerProperties.getGroup(), properties.getOrderTopic());
        return consumer;
    }

    /**
     * 创建并配置消费者，启动和关闭由 Spring Bean 生命周期管理。
     *
     * @param consumerProperties 消费配置
     * @param hook 访问钩子
     * @return 消费者
     * @throws MQClientException 订阅配置失败
     */
    private DefaultMQPushConsumer createConsumer(OrderConsumerProperties consumerProperties, RPCHook hook) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerProperties.getGroup(), hook);
        consumer.setNamesrvAddr(properties.getNameServer());
        consumer.setVipChannelEnabled(properties.isVipChannelEnabled());
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(consumerProperties.getThreads());
        consumer.setConsumeThreadMax(consumerProperties.getThreads());
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setMaxReconsumeTimes(consumerProperties.getMaxReconsumeTimes());
        consumer.subscribe(properties.getOrderTopic(), "CREATE_ORDER");
        return consumer;
    }
}
