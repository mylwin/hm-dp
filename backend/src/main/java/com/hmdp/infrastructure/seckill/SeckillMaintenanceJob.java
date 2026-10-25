package com.hmdp.infrastructure.seckill;

import com.hmdp.config.SeckillMaintenanceProperties;
import com.hmdp.service.ISeckillMaintenanceService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 有界执行持久补偿和恢复，按待办年龄与次数输出可接入日志告警的事件。
 *
 * @author myl
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "hmdp.seckill", name = {"reliable-components-enabled", "maintenance.enabled"}, havingValue = "true")
public class SeckillMaintenanceJob {
    private final ISeckillMaintenanceService maintenance;
    private final SeckillMaintenanceProperties properties;

    /**
     * 启动调度前校验每轮数量、扫描间隔、恢复等待时间、租约及告警阈值。
     *
     * @throws IllegalArgumentException 配置越界，或已开启运维接口但未配置足够长度的独立令牌
     */
    @PostConstruct
    public void validate() {
        properties.validate();
    }

    /**
     * 定时处理一批确认或释放任务，让成功订单的预占完成确认、已关闭请求的预占得到归还。
     * 单轮异常只输出维护失败日志，任务及已登记租约仍保留，等待后续调度再次领取或接管。
     */
    @Scheduled(fixedDelayString = "${hmdp.seckill.maintenance.poll-interval-ms:5000}", initialDelayString = "${hmdp.seckill.maintenance.poll-interval-ms:5000}")
    public void finishReservations() {
        try {
            maintenance.processReservationTasks();
        } catch (RuntimeException ex) {
            log.error("SECKILL_MAINTENANCE_FAILURE action=finish errorType={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * 定时继续发布未完成的新券，并恢复长期 PREPARING 或 ACCEPTED 的原请求。
     * 调用维护 Service 决定是否继续受理或重投原事件，等待过久本身不会触发关闭；单轮失败不取消后续调度。
     */
    @Scheduled(fixedDelayString = "${hmdp.seckill.maintenance.poll-interval-ms:5000}", initialDelayString = "${hmdp.seckill.maintenance.poll-interval-ms:5000}")
    public void recover() {
        try {
            maintenance.recoverDue();
        } catch (RuntimeException ex) {
            log.error("SECKILL_MAINTENANCE_FAILURE action=recover errorType={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * 定时查询最老任务、请求、待发布活动的年龄，以及尝试次数、冲突暂停、隔离和恢复错误。
     * 达到年龄或次数阈值，或存在冲突暂停、未处置隔离、活跃恢复错误时输出积压告警；其余情况记 DEBUG 健康日志。
     * 查询失败记录监控失败日志，下一轮仍检查；不发送邮件短信，也不改变订单状态。
     */
    @Scheduled(fixedDelayString = "${hmdp.seckill.maintenance.monitor-interval-ms:30000}", initialDelayString = "${hmdp.seckill.maintenance.monitor-interval-ms:30000}")
    public void monitor() {
        try {
            Map<String, Object> health = maintenance.health();
            if (number(health, "oldest_task_seconds") >= properties.getAlertAgeSeconds()
                    || number(health, "oldest_request_seconds") >= properties.getAlertAgeSeconds()
                    || number(health, "max_attempts") >= properties.getAlertAttempts()
                    || number(health, "oldest_campaign_seconds") >= properties.getAlertAgeSeconds()
                    || number(health, "blocked_campaigns") > 0
                    || number(health, "quarantine_pending") > 0 || number(health, "recovery_errors") > 0) {
                log.warn("SECKILL_BACKLOG_ALERT metrics={}", health);
            } else {
                log.debug("SECKILL_HEALTH metrics={}", health);
            }
        } catch (RuntimeException ex) {
            log.error("SECKILL_MONITOR_FAILURE errorType={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * 把数据库统计中的数字转为整数，供监控比较告警阈值。
     *
     * @param values 数据库健康统计结果
     * @param key    要读取的指标名称，如 oldest_task_seconds
     * @return 对应 Number 的 long 值；没有该指标或值不是数字时为 0
     */
    private long number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }
}
