package com.hmdp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 维护任务自行启用调度，不依赖 MQ 投递开关。
 *
 * @author myl
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "hmdp.seckill", name = {"reliable-components-enabled", "maintenance.enabled"}, havingValue = "true")
public class SeckillMaintenanceSchedulingConfiguration {
}
