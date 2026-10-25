package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 新链路组件、API 和接单开关；暂停接单时仍保留结果查询及后台收尾。
 *
 * @author myl
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.seckill")
public class SeckillOrderProperties {
    /**
     * 是否启用可靠组件。
     */
    private boolean reliableComponentsEnabled;
    /**
     * 是否开放新链路抢购和结果查询接口，与可靠组件开关共同控制注册。
     */
    private boolean reliableApiEnabled;
    /**
     * 是否接收新的 HTTP 抢购提交；关闭后查询、已受理订单消费及恢复仍继续。
     */
    private boolean acceptingRequests = true;
    /**
     * 短事务超时秒数，单位秒
     */
    private int transactionTimeoutSeconds = 5;

    /**
     * 检查短事务超时的合理范围。
     *
     * @throws IllegalArgumentException 超时不在 1 到 30 秒之间时抛出
     */
    public void validate() {
        if (transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 30) {
            throw new IllegalArgumentException("秒杀受理事务超时必须为 1 到 30 秒");
        }
    }
}
