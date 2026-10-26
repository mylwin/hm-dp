package com.hmdp.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.OrderOutboxProperties;
import com.hmdp.entity.MqOutbox;
import com.hmdp.exception.OrderMessagePublishException;
import com.hmdp.mapper.MqOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 发布任务单元测试，事务原子性另由真实 MySQL 集成测试验证。 */
class OrderOutboxDispatcherTest {
    private final MqOutboxMapper outbox = mock(MqOutboxMapper.class);
    private final RocketMqOrderPublisher publisher = mock(RocketMqOrderPublisher.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final OrderOutboxProperties properties = new OrderOutboxProperties();
    private final AtomicInteger active = new AtomicInteger();
    private OrderOutboxDispatcher dispatcher;

    /** 配置可观测的模拟事务，用于检查 MQ 等待未发生在数据库事务内。 */
    @BeforeEach
    void setUp() {
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            active.incrementAndGet();
            return new SimpleTransactionStatus();
        });
        doAnswer(invocation -> { active.decrementAndGet(); return null; }).when(manager).commit(any());
        doAnswer(invocation -> { active.decrementAndGet(); return null; }).when(manager).rollback(any());
        when(outbox.claim(anyString(), anyString(), anyLong())).thenReturn(1);
        properties.setBatchSize(1);
        dispatcher = new OrderOutboxDispatcher(outbox, publisher, mapper, manager, properties);
    }

    /**
     * 成功路径应先提交租约、在事务外发消息，再提交 SENT。
     * @throws Exception 构造测试 JSON 或模拟发送失败时抛出
     */
    @Test
    void publishesOutsideTransactionAndConfirmsWithLeaseToken() throws Exception {
        MqOutbox event = event();
        when(outbox.lockNextDue()).thenReturn(event);
        when(publisher.publish(any())).thenAnswer(invocation -> {
            assertThat(active.get()).isZero();
            return new RocketMqOrderPublisher.PublishReceipt("order-1", "message-1");
        });
        when(outbox.markSent(eq("order-1"), anyString(), eq("message-1"))).thenReturn(1);
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        verify(outbox).markSent("order-1", event.getLeaseToken(), "message-1");
        verify(outbox, never()).retryLater(anyString(), anyString(), anyLong(), anyString());
        assertThat(active.get()).isZero();
    }

    /**
     * 结果未知时只登记退避，不把超时解释为业务失败。
     * @throws Exception 构造测试消息失败时抛出
     */
    @Test
    void retriesUnknownSendResultWithOriginalEvent() throws Exception {
        MqOutbox event = event();
        event.setAttempts(4);
        when(outbox.lockNextDue()).thenReturn(event);
        when(publisher.publish(any())).thenThrow(new OrderMessagePublishException("投递结果未知"));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        verify(outbox).retryLater("order-1", event.getLeaseToken(), 16000, "OrderMessagePublishException");
        verify(outbox, never()).markSent(anyString(), anyString(), anyString());
    }

    /** 没有任务时不创建生产者调用和回执事务。 */
    @Test
    void emptyQueueDoesNotPublish() {
        assertThat(dispatcher.dispatchDue()).isZero();
        verifyNoInteractions(publisher);
        verify(manager, times(1)).commit(any());
    }

    /** 已中断的调度线程不继续领取消息。 */
    @Test
    void interruptedThreadDoesNotClaimMoreWork() {
        Thread.currentThread().interrupt();
        try {
            assertThat(dispatcher.dispatchDue()).isZero();
            verifyNoInteractions(outbox, publisher, manager);
        } finally {
            Thread.interrupted();
        }
    }

    /** 非法消息保留在 Outbox，不发送给 Broker。 */
    @Test
    void corruptPayloadIsRetainedForRetry() {
        MqOutbox event = new MqOutbox();
        event.setEventId("order-1");
        event.setAttempts(0);
        event.setPayload("invalid-json");
        when(outbox.lockNextDue()).thenReturn(event);
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        verifyNoInteractions(publisher);
        verify(outbox).retryLater(eq("order-1"), anyString(), eq(1000L), anyString());
    }

    /** 反复失败的消息持续保留，重试延迟不会溢出或无限增长。 */
    @Test
    void backoffIsBoundedForLongRunningFailure() {
        assertThat(OrderOutboxDispatcher.retryDelayMs(1)).isEqualTo(1000);
        assertThat(OrderOutboxDispatcher.retryDelayMs(10)).isEqualTo(300000);
        assertThat(OrderOutboxDispatcher.retryDelayMs(Integer.MAX_VALUE)).isEqualTo(300000);
    }

    /** 外层已有事务时拒绝投递，避免 REQUIRES_NEW 返回后在外层事务中等待 MQ。 */
    @Test
    void rejectsCallerWithActiveTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> dispatcher.dispatchDue()).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(outbox, publisher, manager);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * 构造持久化消息样本。
     * @return 首次待发送事件
     * @throws Exception JSON 序列化失败时抛出
     */
    private MqOutbox event() throws Exception {
        MqOutbox event = new MqOutbox();
        event.setEventId("order-1");
        event.setOrderId(1L);
        event.setSchemaVersion(1);
        event.setAttempts(0);
        event.setPayload(mapper.writeValueAsString(new SeckillOrderMessage(1, "order-1", 1L, 2L, 3L,
                "trace-1", Instant.parse("2026-10-24T00:00:00Z"))));
        return event;
    }
}
