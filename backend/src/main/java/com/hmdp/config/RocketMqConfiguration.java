package com.hmdp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.remoting.RPCHook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 第一阶段只注册生产者，不注册订单消费者或改变 Stream 下单流程。
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
}
