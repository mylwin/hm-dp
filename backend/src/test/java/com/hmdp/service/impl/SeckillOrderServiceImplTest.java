package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.constants.SeckillRequestStatus;
import com.hmdp.entity.*;
import com.hmdp.infrastructure.mq.SeckillOrderMessage;
import com.hmdp.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证业务分支及事务调用顺序；数据库回滚效果另由真实 MySQL 测试验证。 */
class SeckillOrderServiceImplTest {
    private final SeckillCampaignControlMapper campaigns = mock(SeckillCampaignControlMapper.class);
    private final SeckillRequestMapper requests = mock(SeckillRequestMapper.class);
    private final VoucherOrderMapper orders = mock(VoucherOrderMapper.class);
    private final SeckillVoucherMapper vouchers = mock(SeckillVoucherMapper.class);
    private final MqOutboxMapper outbox = mock(MqOutboxMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SeckillOrderMessage message = new SeckillOrderMessage(1, "order-1001", 1001L, 1L, 30L,
            "trace-1", Instant.parse("2026-10-24T00:00:00Z"));
    private SeckillRequest request;
    private SeckillOrderServiceImpl service;

    /** 准备已受理请求、活动控制及可观测的模拟事务。 */
    @BeforeEach
    void setUp() {
        SeckillCampaignControl campaign = new SeckillCampaignControl();
        campaign.setVoucherId(30L); campaign.setEpoch(1L); campaign.setStatus("READY");
        request = new SeckillRequest();
        request.setOrderId(1001L); request.setUserId(1L); request.setVoucherId(30L);
        request.setTraceId("trace-1"); request.setAcceptedAt(message.acceptedAt());
        request.setStatus(SeckillRequestStatus.ACCEPTED); request.setReservationEpoch(1L);
        when(campaigns.lockShared(30L)).thenReturn(campaign);
        when(requests.lockByOrderId(1001L)).thenReturn(request);
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(vouchers.deductOne(30L)).thenReturn(1);
        when(orders.insertSeckillOrder(any())).thenReturn(1);
        when(requests.markSucceeded(1001L)).thenReturn(1);
        when(requests.markClosed(eq(1001L), anyString())).thenReturn(1);
        when(outbox.insertEvent(any())).thenReturn(1);
        service = new SeckillOrderServiceImpl(campaigns, requests, orders, vouchers, outbox,
                new ObjectMapper().findAndRegisterModules(), manager, new SeckillOrderProperties());
    }

    /** 正常建单必须先锁活动和请求，全部写入后再提交。 */
    @Test
    void createsAndRegistersConfirmationBeforeCommit() {
        service.createFromMessage(message);
        var sequence = inOrder(campaigns, requests, vouchers, orders, outbox, manager);
        sequence.verify(campaigns).lockShared(30L);
        sequence.verify(requests).lockByOrderId(1001L);
        sequence.verify(vouchers).deductOne(30L);
        sequence.verify(orders).insertSeckillOrder(any());
        sequence.verify(requests).markSucceeded(1001L);
        sequence.verify(outbox).insertEvent(argThat(event -> "confirm-1001".equals(event.getEventId())
                && "CONFIRM_RESERVATION".equals(event.getEventType())));
        sequence.verify(manager).commit(any());
    }

    /** 已成功的消息重投只核对订单，不重复扣库或登记任务。 */
    @Test
    void succeededReplayDoesNotDeductAgain() {
        request.setStatus(SeckillRequestStatus.SUCCEEDED);
        when(orders.findOrder(1001L)).thenReturn(new VoucherOrder().setId(1001L).setUserId(1L).setVoucherId(30L));
        service.createFromMessage(message);
        verifyNoInteractions(vouchers, outbox);
        verify(orders, never()).insertSeckillOrder(any());
    }

    /** 已关闭消息结束消费，不重新创建订单。 */
    @Test
    void closedReplayDoesNotCreate() {
        request.setStatus(SeckillRequestStatus.CLOSED);
        service.createFromMessage(message);
        verifyNoInteractions(orders, vouchers, outbox);
    }

    /** PREPARING 不能仅凭消息直接创建订单。 */
    @Test
    void preparingWaitsForRecovery() {
        request.setStatus(SeckillRequestStatus.PREPARING);
        assertThatThrownBy(() -> service.createFromMessage(message)).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(any());
        verifyNoInteractions(orders, vouchers, outbox);
    }

    /** 消息冒用用户身份时回滚，不触碰库存。 */
    @Test
    void mismatchedMessageDoesNotTouchStock() {
        SeckillOrderMessage forged = new SeckillOrderMessage(1, "order-1001", 1001L, 2L, 30L,
                "trace-1", message.acceptedAt());
        assertThatThrownBy(() -> service.createFromMessage(forged)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vouchers, orders, outbox);
    }

    /** 库存不足先回滚建单事务，再另开事务关闭并登记释放。 */
    @Test
    void stockFailureRollsBackBeforeClosure() {
        when(vouchers.deductOne(30L)).thenReturn(0);
        service.createFromMessage(message);
        var sequence = inOrder(manager, requests, outbox);
        sequence.verify(manager).rollback(any());
        sequence.verify(manager).getTransaction(any());
        sequence.verify(requests).markClosed(1001L, "DB_STOCK_INSUFFICIENT");
        sequence.verify(outbox).insertEvent(argThat(event -> "RELEASE_RESERVATION".equals(event.getEventType())));
        sequence.verify(manager).commit(any());
        verify(orders, never()).insertSeckillOrder(any());
    }

    /** 确认任务写入失败时整个建单事务回滚，不能返回成功。 */
    @Test
    void taskFailureDoesNotCommitSuccess() {
        when(outbox.insertEvent(any())).thenThrow(new IllegalStateException("故障注入"));
        assertThatThrownBy(() -> service.createFromMessage(message)).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
    }

    /** 唯一键冲突但找不到相应订单时保留异常，不误关闭请求。 */
    @Test
    void unrelatedDuplicateKeyIsNotBusinessClosure() {
        when(orders.insertSeckillOrder(any())).thenThrow(new DuplicateKeyException("故障注入"));
        assertThatThrownBy(() -> service.createFromMessage(message)).isInstanceOf(DuplicateKeyException.class);
        verify(requests, never()).markClosed(anyLong(), anyString());
        verify(manager, times(2)).rollback(any());
    }

    /** 外层事务尚未结束时禁止消费，防止提前返回 MQ 确认。 */
    @Test
    void rejectsOuterTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.createFromMessage(message)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(manager, vouchers);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    /** 无唯一约束时启动检查失败。 */
    @Test
    void missingUniqueIndexBlocksStartup() {
        assertThatThrownBy(service::verifySchema).isInstanceOf(IllegalStateException.class);
        when(orders.countUserVoucherUniqueIndexes()).thenReturn(1);
        assertThatCode(service::verifySchema).doesNotThrowAnyException();
    }
}
