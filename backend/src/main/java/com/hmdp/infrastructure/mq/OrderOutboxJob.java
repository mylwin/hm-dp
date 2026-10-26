package com.hmdp.infrastructure.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 随应用运行的有界扫描任务，多实例依靠数据库租约协调。
 *
 * @author myl
 */
@Component
@RequiredArgsConstructor
@Slf4j
// 只有 Mq 和 Outbox 都启用时才创建该任务
@ConditionalOnProperty(prefix = "hmdp.rocketmq", name = {"enabled", "outbox.enabled"}, havingValue = "true")
public class OrderOutboxJob {
    private final OrderOutboxDispatcher dispatcher;

    /**
     * 扫描到期事件；数据库故障交由下一轮调度重试，未确认任务仍保留。
     */
    @Scheduled(fixedDelayString = "${hmdp.rocketmq.outbox.poll-interval-ms:1000}",
            initialDelayString = "${hmdp.rocketmq.outbox.poll-interval-ms:1000}")
    public void dispatch() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException ex) {
            log.warn("Outbox 扫描或状态保存失败，未确认任务保留，等待后续接管", ex);
        }
    }
}
