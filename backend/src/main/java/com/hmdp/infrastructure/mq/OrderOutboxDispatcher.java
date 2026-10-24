package com.hmdp.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.OrderOutboxProperties;
import com.hmdp.entity.MqOutbox;
import com.hmdp.mapper.MqOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * 数据库短事务领取，事务外同步发送，再用当前租约确认或登记退避。
 *
 * @author myl
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "hmdp.rocketmq", name = {"enabled", "outbox.enabled"}, havingValue = "true")
public class OrderOutboxDispatcher {
    private final MqOutboxMapper outbox;
    private final RocketMqOrderPublisher publisher;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final OrderOutboxProperties properties;

    /**
     * 限量投递本轮到期任务，遇到线程中断立即停止领取新消息。
     *
     * @return 本轮实际领取的任务数，不等于建单成功数
     * @throws IllegalStateException 调用线程已经处于数据库事务中时抛出
     */
    public int dispatchDue() {
        // 如果当前线程已经处于数据库事务中，则抛出异常
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox 投递不能从数据库事务内调用");
        }
        // 验证配置
        properties.validate();
        int processed = 0;
        // 循环领取任务并发送，直到达到批次限制或线程中断
        while (processed < properties.getBatchSize() && !Thread.currentThread().isInterrupted()) {
            MqOutbox event = claimNext();
            if (event == null) {
                break;
            }
            processed++;
            publishClaimed(event);
        }
        return processed;
    }

    /**
     * 领取一条任务并提交租约，进程退出后允许租约过期接管。
     *
     * @return 当前租约任务，无任务时为 null
     */
    private MqOutbox claimNext() {
        return transaction().execute(status -> {
            // 锁定并领取下一条到期任务
            MqOutbox event = outbox.lockNextDue();
            // 如果没有任务则返回 null
            if (event == null) {
                return null;
            }
            // 租约令牌
            String token = UUID.randomUUID().toString();
            // 如果租约失败则抛出异常
            if (outbox.claim(event.getEventId(), token, properties.getLeaseMs()) != 1) {
                throw new IllegalStateException("Outbox 租约领取失败");
            }
            // 设置租约令牌
            event.setLeaseToken(token);
            // 增加尝试次数
            event.setAttempts(event.getAttempts() == Integer.MAX_VALUE ? Integer.MAX_VALUE : event.getAttempts() + 1);
            return event;
        });
    }

    /**
     * 发送租约内的原始业务事件，失败仅登记重试，不操作 Redis 库存。
     *
     * @param event 已提交租约的消息
     */
    private void publishClaimed(MqOutbox event) {
        try {
            // 反序列化消息
            SeckillOrderMessage message = objectMapper.readValue(event.getPayload(), SeckillOrderMessage.class);
            // 验证消息标识
            if (!Objects.equals(message.eventId(), event.getEventId())
                    || !Objects.equals(message.orderId(), event.getOrderId())
                    || message.schemaVersion() != event.getSchemaVersion()) {
                throw new IllegalStateException("Outbox 消息标识与持久化记录不一致");
            }
            // 发送消息
            RocketMqOrderPublisher.PublishReceipt receipt = publisher.publish(message);
            // MQ 发送已经结束后才开启回执事务，数据库锁不会覆盖网络等待。
            Integer updated = transaction().execute(status -> outbox.markSent(
                    event.getEventId(), event.getLeaseToken(), receipt.messageId()));
            // 如果数据库更新行数为 0，则表示租约已失效
            if (Objects.equals(updated, 0)) {
                log.debug("Outbox 发送回执未更新，租约已失效，eventId={}", event.getEventId());
            }
        } catch (JsonProcessingException | RuntimeException ex) {
            // 消息发送失败，登记重试
            long delay = retryDelayMs(event.getAttempts());
            transaction().executeWithoutResult(status -> outbox.retryLater(event.getEventId(), event.getLeaseToken(),
                    delay, ex.getClass().getSimpleName()));
            log.debug("Outbox 保留重试，eventId={}，attempt={}，delayMs={}",
                    event.getEventId(), event.getAttempts(), delay);
        }
    }

    /**
     * 创建独立的五秒短事务，不在事务中调用 RocketMQ。
     *
     * @return 用于领取和回执更新的事务模板
     */
    private TransactionTemplate transaction() {
        // 创建一个事务模板，设置为新事务，读提交隔离，超时 5 秒
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(5);
        return template;
    }

    /**
     * 按尝试次数计算指数退避，最高五分钟，不因超过重试次数丢弃消息。
     *
     * @param attempts 已领取尝试次数，首次为 1
     * @return 距下次发送的毫秒数
     */
    static long retryDelayMs(int attempts) {
        return Math.min(300000L, 1000L << Math.min(19, Math.max(0, attempts - 1)));
    }
}
