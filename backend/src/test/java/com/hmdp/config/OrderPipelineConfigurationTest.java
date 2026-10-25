package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.infrastructure.mq.OrderOutboxDispatcher;
import com.hmdp.infrastructure.mq.OrderOutboxJob;
import com.hmdp.infrastructure.mq.OrderMessageFailureStore;
import com.hmdp.infrastructure.mq.RocketMqDeadLetterListener;
import com.hmdp.infrastructure.seckill.SeckillMaintenanceJob;
import com.hmdp.controller.SeckillMaintenanceController;
import com.hmdp.service.ISeckillMaintenanceService;
import com.hmdp.infrastructure.mq.RocketMqOrderPublisher;
import com.hmdp.infrastructure.mq.RocketMqOrderListener;
import com.hmdp.service.ISeckillOrderService;
import com.hmdp.controller.SeckillRequestController;
import com.hmdp.infrastructure.redis.RedisIdGenerator;
import com.hmdp.infrastructure.seckill.SeckillReservationStore;
import com.hmdp.mapper.MqOutboxMapper;
import com.hmdp.mapper.SeckillCampaignControlMapper;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.ISeckillRequestService;
import com.hmdp.service.impl.SeckillRequestServiceImpl;
import com.hmdp.service.impl.VoucherServiceImpl;
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
            .withBean(VoucherMapper.class, () -> mock(VoucherMapper.class))
            .withBean(ISeckillVoucherService.class, () -> mock(ISeckillVoucherService.class))
            .withBean(SeckillCampaignControlMapper.class, () -> mock(SeckillCampaignControlMapper.class))
            .withBean(MqOutboxMapper.class, () -> mock(MqOutboxMapper.class))
            .withBean(RedisIdGenerator.class, () -> mock(RedisIdGenerator.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(RocketMqOrderPublisher.class, () -> mock(RocketMqOrderPublisher.class))
            .withBean(ISeckillOrderService.class, () -> mock(ISeckillOrderService.class))
            .withBean(OrderMessageFailureStore.class, () -> mock(OrderMessageFailureStore.class))
            .withBean(ISeckillMaintenanceService.class, () -> mock(ISeckillMaintenanceService.class))
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

    @Configuration(proxyBeanMethods = false)
    @Import({SeckillOrderProperties.class, OrderOutboxProperties.class, SeckillRequestServiceImpl.class,
            SeckillReservationStore.class, OrderOutboxDispatcher.class, OrderOutboxJob.class,
            RocketMqOrderListener.class, SeckillRequestController.class, VoucherServiceImpl.class,
            SeckillMaintenanceProperties.class, SeckillMaintenanceJob.class, SeckillMaintenanceController.class,
            RocketMqDeadLetterListener.class})
    static class PipelineConfiguration {
    }

    /** 默认不注册新受理组件，也不启动 Outbox 扫描。 */
    @Test
    void disabledByDefaultDoesNotRegisterNewPipeline() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IVoucherService.class);
            assertThat(context).doesNotHaveBean(ISeckillRequestService.class);
            assertThat(context).doesNotHaveBean(OrderOutboxDispatcher.class);
            assertThat(context).doesNotHaveBean(OrderOutboxJob.class);
            assertThat(context).doesNotHaveBean(RocketMqOrderListener.class);
            assertThat(context).doesNotHaveBean(SeckillRequestController.class);
            assertThat(context).doesNotHaveBean(SeckillMaintenanceJob.class);
            assertThat(context).doesNotHaveBean(SeckillMaintenanceController.class);
            assertThat(context).doesNotHaveBean(RocketMqDeadLetterListener.class);
        });
    }

    /** 新 API 和监听器都需要显式开关，不能只启用受理组件就暴露。 */
    @Test
    void newApiAndListenerRequireAllSwitches() {
        runner.withPropertyValues("hmdp.seckill.reliable-components-enabled=true",
                "hmdp.seckill.reliable-api-enabled=true", "hmdp.rocketmq.enabled=true",
                "hmdp.rocketmq.consumer.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RocketMqOrderListener.class);
            assertThat(context).hasSingleBean(SeckillRequestController.class);
        });
        runner.withPropertyValues("hmdp.seckill.reliable-api-enabled=true",
                "hmdp.rocketmq.enabled=true", "hmdp.rocketmq.consumer.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RocketMqOrderListener.class);
            assertThat(context).doesNotHaveBean(SeckillRequestController.class);
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

    /** 维护可独立运行，运维接口及死信消费者仍需各自开关。 */
    @Test
    void maintenanceAndOperationsHaveIndependentSwitches() {
        runner.withPropertyValues("hmdp.seckill.reliable-components-enabled=true",
                "hmdp.seckill.maintenance.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SeckillMaintenanceJob.class);
            assertThat(context).doesNotHaveBean(SeckillMaintenanceController.class);
            assertThat(context).doesNotHaveBean(RocketMqDeadLetterListener.class);
        });
        runner.withPropertyValues("hmdp.seckill.reliable-components-enabled=true",
                "hmdp.seckill.maintenance.operations-enabled=true",
                "hmdp.seckill.maintenance.operations-token=test-token-at-least-32-characters-long").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SeckillMaintenanceController.class);
        });
        runner.withPropertyValues("hmdp.seckill.maintenance.enabled=true",
                "hmdp.seckill.maintenance.operations-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SeckillMaintenanceJob.class);
            assertThat(context).doesNotHaveBean(SeckillMaintenanceController.class);
        });
    }
}
