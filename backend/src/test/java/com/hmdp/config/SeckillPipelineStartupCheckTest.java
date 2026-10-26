package com.hmdp.config;

import com.hmdp.controller.VoucherOrderController;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** 检查实际配置绑定、完整启动条件及旧组件停用，不连接 Redis、MySQL 或 MQ。 */
class SeckillPipelineStartupCheckTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PipelineConfiguration.class);
    private final String[] completeConfiguration = {
            "hmdp.seckill.reliable-components-enabled=true", "hmdp.seckill.reliable-api-enabled=true",
            "hmdp.rocketmq.enabled=true", "hmdp.rocketmq.outbox.enabled=true",
            "hmdp.rocketmq.consumer.enabled=true", "hmdp.rocketmq.consumer.dead-letter-enabled=true",
            "hmdp.seckill.maintenance.enabled=true"
    };

    /** 绑定生产所用的属性类和启动检查，不注册中间件客户端。 */
    @Configuration(proxyBeanMethods = false)
    @Import({SeckillOrderProperties.class, RocketMqProperties.class, OrderOutboxProperties.class,
            OrderConsumerProperties.class, SeckillMaintenanceProperties.class, SeckillPipelineStartupCheck.class})
    static class PipelineConfiguration { }

    /** 默认关闭 API 的工作实例可按自身职责独立运行。 */
    @Test
    void workerWithoutApiDoesNotRequireAllComponents() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    /** 完整开关允许启动，暂停接单后仍保留完整管道。 */
    @Test
    void completePipelineStartsWithAdmissionPaused() {
        runner.withPropertyValues(completeConfiguration).withPropertyValues("hmdp.seckill.accepting-requests=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SeckillOrderProperties.class).isAcceptingRequests()).isFalse();
                });
    }

    /** API 依赖的任一环节缺失都须拒绝启动，不能接单后才发现后台未启用。 */
    @Test
    void missingPipelineComponentPreventsApiStartup() {
        for (String setting : completeConfiguration) {
            if (setting.contains("reliable-api-enabled")) continue;
            String disabledSetting = setting.replace("=true", "=false");
            runner.withPropertyValues(completeConfiguration).withPropertyValues(disabledSetting).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("秒杀 API 要求启用");
            });
        }
    }

    /** 采用真实组件扫描确认历史 Controller 和 Service 均不再注册，不会自动启动 Stream 线程。 */
    @Test
    void componentScanDoesNotRegisterLegacyEntrypointOrConsumer() {
        new ApplicationContextRunner().withUserConfiguration(LegacyScanConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(VoucherOrderController.class);
            assertThat(context).doesNotHaveBean(VoucherOrderServiceImpl.class);
        });
    }

    /** 只纳入两个旧类作为扫描候选，保留默认的组件注解识别规则。 */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = {VoucherOrderController.class, VoucherOrderServiceImpl.class},
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.hmdp\\.(controller|service\\.impl)\\.(?!VoucherOrderController$|VoucherOrderServiceImpl$).*"))
    static class LegacyScanConfiguration { }
}
