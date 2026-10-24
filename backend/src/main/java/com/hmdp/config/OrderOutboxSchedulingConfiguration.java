package com.hmdp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 仅显式启用 Outbox 时开启调度；开关不用于切换订单业务通道。
 *
 * @author myl
 */
@Configuration(proxyBeanMethods = false)
// 开启定时任务
@EnableScheduling
// 只有 Mq 和 Outbox 都启用时才开启调度
@ConditionalOnProperty(prefix = "hmdp.rocketmq", name = {"enabled", "outbox.enabled"}, havingValue = "true")
public class OrderOutboxSchedulingConfiguration {
}
