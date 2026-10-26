package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.infrastructure.mq.RocketMqOrderPublisher;
import com.hmdp.infrastructure.mq.RocketMqOrderListener;
import com.hmdp.infrastructure.mq.RocketMqDeadLetterListener;
import com.hmdp.service.ISeckillOrderService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RocketMqConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

    @Configuration(proxyBeanMethods = false)
    @Import(RocketMqConfiguration.class)
    @ComponentScan(basePackageClasses = {RocketMqProperties.class, RocketMqOrderPublisher.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {RocketMqProperties.class, RocketMqOrderPublisher.class}))
    static class TestConfiguration {
    }

    /**
     * 死信消费者订阅原建单组的系统死信 Topic，使用独立组并随上下文关闭。
     * @throws Exception 客户端订阅测试失败
     */
    @Test
    void deadLetterConsumerUsesDedicatedGroupAndLifecycle() throws Exception {
        RocketMqDeadLetterListener listener = mock(RocketMqDeadLetterListener.class);
        AtomicReference<DefaultMQPushConsumer> observed = new AtomicReference<>();
        try (var producer = mockConstruction(DefaultMQProducer.class);
             var consumers = mockConstruction(DefaultMQPushConsumer.class)) {
            runner.withBean(OrderConsumerProperties.class, OrderConsumerProperties::new)
                    .withBean(ISeckillOrderService.class, () -> mock(ISeckillOrderService.class))
                    .withBean(RocketMqOrderListener.class, () -> mock(RocketMqOrderListener.class))
                    .withBean(RocketMqDeadLetterListener.class, () -> listener)
                    .withPropertyValues("hmdp.rocketmq.enabled=true", "hmdp.rocketmq.consumer.enabled=true",
                            "hmdp.rocketmq.consumer.dead-letter-enabled=true", "hmdp.seckill.reliable-components-enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        observed.set(context.getBean("rocketMqDeadLetterConsumer", DefaultMQPushConsumer.class));
                        verify(observed.get()).subscribe("%DLQ%hmdp-seckill-order-create-cg", "*");
                        verify(observed.get()).registerMessageListener(listener);
                        verify(observed.get()).setMaxReconsumeTimes(Integer.MAX_VALUE);
                        verify(observed.get()).start();
                    });
            verify(observed.get()).shutdown();
        }
    }

    /**
     * 消费者启动前先检查唯一索引，并随 Spring 上下文关闭。
     * @throws Exception MQ 客户端生命周期测试调用失败
     */
    @Test
    void consumerVerifiesSchemaAndFollowsContextLifecycle() throws Exception {
        ISeckillOrderService orders = mock(ISeckillOrderService.class);
        RocketMqOrderListener listener = mock(RocketMqOrderListener.class);
        AtomicReference<DefaultMQPushConsumer> client = new AtomicReference<>();
        try (var producer = mockConstruction(DefaultMQProducer.class);
             var consumer = mockConstruction(DefaultMQPushConsumer.class)) {
            runner.withBean(OrderConsumerProperties.class, OrderConsumerProperties::new)
                    .withBean(ISeckillOrderService.class, () -> orders)
                    .withBean(RocketMqOrderListener.class, () -> listener)
                    .withPropertyValues("hmdp.rocketmq.enabled=true", "hmdp.rocketmq.consumer.enabled=true",
                            "hmdp.seckill.reliable-components-enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        client.set(context.getBean(DefaultMQPushConsumer.class));
                        verify(orders).verifySchema();
                        verify(client.get()).setConsumeMessageBatchMaxSize(1);
                        verify(client.get()).subscribe("hmdp-seckill-order-create", "CREATE_ORDER");
                        verify(client.get()).registerMessageListener(listener);
                        verify(client.get()).start();
                    });
            verify(client.get()).shutdown();
            assertThat(consumer.constructed()).hasSize(1);
        }
    }

    /** 未完成数据库唯一索引迁移时不得构造消费者。 */
    @Test
    void missingIndexBlocksConsumerBeforeConstruction() {
        ISeckillOrderService orders = mock(ISeckillOrderService.class);
        doThrow(new IllegalStateException("索引缺失")).when(orders).verifySchema();
        try (var producer = mockConstruction(DefaultMQProducer.class);
             var consumer = mockConstruction(DefaultMQPushConsumer.class)) {
            runner.withBean(OrderConsumerProperties.class, OrderConsumerProperties::new)
                    .withBean(ISeckillOrderService.class, () -> orders)
                    .withBean(RocketMqOrderListener.class, () -> mock(RocketMqOrderListener.class))
                    .withPropertyValues("hmdp.rocketmq.enabled=true", "hmdp.rocketmq.consumer.enabled=true",
                            "hmdp.seckill.reliable-components-enabled=true")
                    .run(context -> assertThat(context).hasFailed());
            assertThat(consumer.constructed()).isEmpty();
        }
    }

    @Test
    void disabledByDefaultDoesNotConstructOrStartMqClients() {
        try (MockedConstruction<DefaultMQProducer> construction = mockConstruction(DefaultMQProducer.class)) {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(DefaultMQProducer.class);
                assertThat(context).doesNotHaveBean(RocketMqOrderPublisher.class);
                assertThat(construction.constructed()).isEmpty();
            });
        }
    }

    @Test
    void explicitlyDisabledMqDoesNotRegisterClients() {
        runner.withPropertyValues("hmdp.rocketmq.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RocketMqProperties.class);
            assertThat(context).doesNotHaveBean(DefaultMQProducer.class);
            assertThat(context).doesNotHaveBean(RocketMqOrderPublisher.class);
        });
    }

    @Test
    void enabledClientUsesBoundSettingsAndShutsDownWithContext() throws Exception {
        AtomicReference<DefaultMQProducer> reference = new AtomicReference<>();
        try (MockedConstruction<DefaultMQProducer> construction = mockConstruction(DefaultMQProducer.class)) {
            runner.withPropertyValues("hmdp.rocketmq.enabled=true",
                            "hmdp.rocketmq.name-server=127.0.0.1:19876",
                            "hmdp.rocketmq.send-timeout=1500ms", "hmdp.rocketmq.send-retries=1")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(RocketMqOrderPublisher.class);
                        DefaultMQProducer producer = context.getBean(DefaultMQProducer.class);
                        reference.set(producer);
                        verify(producer).setNamesrvAddr("127.0.0.1:19876");
                        verify(producer).setSendMsgTimeout(1500);
                        verify(producer).setRetryTimesWhenSendFailed(1);
                        verify(producer).setVipChannelEnabled(false);
                        verify(producer).start();
                    });
            assertThat(construction.constructed()).hasSize(1);
            verify(reference.get()).shutdown();
        }
    }

    @Test
    void invalidEnabledConfigurationFailsBeforeClientConstruction() {
        try (MockedConstruction<DefaultMQProducer> construction = mockConstruction(DefaultMQProducer.class)) {
            runner.withPropertyValues("hmdp.rocketmq.enabled=true",
                            "hmdp.rocketmq.name-server=http://10.211.55.9:9876")
                    .run(context -> assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class));
            assertThat(construction.constructed()).isEmpty();
        }
    }

    @Test
    void aclCredentialsArePassedAsRpcHookWhenConfigured() {
        try (MockedConstruction<DefaultMQProducer> ignored = mockConstruction(DefaultMQProducer.class,
                (mock, context) -> assertThat(context.arguments().get(1)).isInstanceOf(AclClientRPCHook.class))) {
            runner.withPropertyValues("hmdp.rocketmq.enabled=true",
                            "hmdp.rocketmq.access-key=test-access", "hmdp.rocketmq.secret-key=test-secret")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }
}
