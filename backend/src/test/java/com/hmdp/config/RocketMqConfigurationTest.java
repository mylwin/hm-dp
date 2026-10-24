package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.infrastructure.mq.RocketMqOrderPublisher;
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
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

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
