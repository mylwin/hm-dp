package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.infrastructure.mq.OrderOutboxDispatcher;
import com.hmdp.infrastructure.mq.OrderOutboxJob;
import com.hmdp.infrastructure.mq.RocketMqOrderPublisher;
import com.hmdp.infrastructure.redis.RedisIdGenerator;
import com.hmdp.infrastructure.seckill.SeckillReservationStore;
import com.hmdp.mapper.MqOutboxMapper;
import com.hmdp.mapper.SeckillCampaignControlMapper;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.service.ISeckillRequestService;
import com.hmdp.service.impl.SeckillRequestServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 检查第二阶段组件默认关闭及依赖的条件注册，不访问真实数据库或 MQ。 */
class OrderPipelineConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PipelineConfiguration.class)
            .withBean(SeckillRequestMapper.class, () -> mock(SeckillRequestMapper.class))
            .withBean(SeckillCampaignControlMapper.class, () -> mock(SeckillCampaignControlMapper.class))
            .withBean(MqOutboxMapper.class, () -> mock(MqOutboxMapper.class))
            .withBean(RedisIdGenerator.class, () -> mock(RedisIdGenerator.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(RocketMqOrderPublisher.class, () -> mock(RocketMqOrderPublisher.class))
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

    @Configuration(proxyBeanMethods = false)
    @Import({SeckillOrderProperties.class, OrderOutboxProperties.class, SeckillRequestServiceImpl.class,
            SeckillReservationStore.class, OrderOutboxDispatcher.class, OrderOutboxJob.class})
    static class PipelineConfiguration {
    }

    /** 默认不注册新受理组件，也不启动 Outbox 扫描。 */
    @Test
    void disabledByDefaultDoesNotRegisterNewPipeline() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ISeckillRequestService.class);
            assertThat(context).doesNotHaveBean(OrderOutboxDispatcher.class);
            assertThat(context).doesNotHaveBean(OrderOutboxJob.class);
        });
    }

    /** 新受理组件可独立测试，启用它不会自动启动消息投递。 */
    @Test
    void acceptanceComponentsDoNotSwitchOnOutbox() {
        runner.withPropertyValues("hmdp.seckill.reliable-components-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ISeckillRequestService.class);
            assertThat(context).hasSingleBean(SeckillReservationStore.class);
            assertThat(context).doesNotHaveBean(OrderOutboxDispatcher.class);
        });
    }

    /** 生产者和 Outbox 同时启用才注册投递器及任务。 */
    @Test
    void outboxRequiresEnabledMqAndOwnSwitch() {
        runner.withPropertyValues("hmdp.rocketmq.enabled=true", "hmdp.rocketmq.outbox.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OrderOutboxDispatcher.class);
            assertThat(context).hasSingleBean(OrderOutboxJob.class);
        });
        runner.withPropertyValues("hmdp.rocketmq.enabled=false", "hmdp.rocketmq.outbox.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OrderOutboxDispatcher.class);
            assertThat(context).doesNotHaveBean(OrderOutboxJob.class);
        });
    }
}
