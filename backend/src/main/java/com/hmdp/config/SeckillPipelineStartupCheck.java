package com.hmdp.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 开放抢购 API 前检查新链路必需的组件开关，避免接口已接单却没有投递、消费或补偿任务。
 * 只检查配置完整性；运行期间的网络故障由原请求账本及持久任务恢复。
 *
 * @author myl
 */
@Component
@RequiredArgsConstructor
public class SeckillPipelineStartupCheck implements SmartInitializingSingleton {
    private final SeckillOrderProperties seckillOrderProperties;
    private final RocketMqProperties rocketMqProperties;
    private final OrderOutboxProperties orderOutboxProperties;
    private final OrderConsumerProperties orderConsumerProperties;
    private final SeckillMaintenanceProperties seckillMaintenanceProperties;

    /**
     * API 开启时要求受理组件、MQ、Outbox、订单消费、死信隔离和维护调度一起开启，否则启动失败。
     * API 关闭的独立工作实例仍可按各自开关运行；暂停提交也保留对已有请求的完整收尾能力。
     *
     * @throws IllegalStateException API 开启但必需组件开关不完整
     */
    @Override
    public void afterSingletonsInstantiated() {
        // 未开放秒杀 API 的实例无需同时启用全部订单组件。
        if (!seckillOrderProperties.isReliableApiEnabled()) return;
        // API 已开放时，以下任一组件关闭都拒绝启动，避免受理后无人投递、建单或收尾。
        if (!seckillOrderProperties.isReliableComponentsEnabled() || !rocketMqProperties.isEnabled() || !orderOutboxProperties.isEnabled()
                || !orderConsumerProperties.isEnabled() || !orderConsumerProperties.isDeadLetterEnabled() || !seckillMaintenanceProperties.isEnabled()) {
            throw new IllegalStateException("秒杀 API 要求启用可靠组件、MQ、Outbox、订单消费、死信隔离和维护调度");
        }
    }
}
