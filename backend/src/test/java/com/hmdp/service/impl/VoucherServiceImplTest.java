package com.hmdp.service.impl;

import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.entity.SeckillCampaignControl;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.exception.SeckillCampaignPublicationPendingException;
import com.hmdp.infrastructure.seckill.SeckillReservationStore;
import com.hmdp.mapper.SeckillCampaignControlMapper;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证发券分支与事务调用顺序；真实回滚和 Lua 效果由隔离集成测试验证。 */
class VoucherServiceImplTest {
    private final ISeckillVoucherService vouchers = mock(ISeckillVoucherService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SeckillCampaignControlMapper campaigns = mock(SeckillCampaignControlMapper.class);
    private final SeckillReservationStore store = mock(SeckillReservationStore.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SeckillReservationStore> provider = mock(ObjectProvider.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SeckillOrderProperties properties = new SeckillOrderProperties();
    private final LocalDateTime begin = LocalDateTime.of(2026, 10, 25, 0, 0);
    private VoucherServiceImpl service;
    private Voucher voucher;
    private SeckillCampaignControl campaign;

    /** 准备待发布新券和可观察提交顺序的事务替身。 */
    @BeforeEach
    void setUp() {
        properties.setReliableComponentsEnabled(true);
        when(provider.getIfAvailable()).thenReturn(store);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        service = spy(new VoucherServiceImpl(vouchers, redis, campaigns, provider, manager, properties));
        doAnswer(call -> { ((Voucher) call.getArgument(0)).setId(30L); return true; })
                .when(service).save(any(Voucher.class));
        when(vouchers.save(any())).thenReturn(true);
        when(vouchers.getById(30L)).thenReturn(new SeckillVoucher().setVoucherId(30L).setStock(10)
                .setBeginTime(begin).setEndTime(begin.plusDays(1)));
        when(campaigns.insertPending(30L)).thenReturn(1);
        when(campaigns.markReady(30L, 1L)).thenReturn(1);
        campaign = new SeckillCampaignControl();
        campaign.setVoucherId(30L); campaign.setEpoch(1L); campaign.setStatus("PAUSED");
        campaign.setPublicationStatus("PENDING");
        when(campaigns.lockExclusive(30L)).thenReturn(campaign);
        voucher = new Voucher().setStock(10).setBeginTime(begin).setEndTime(begin.plusDays(1));
    }

    /** 新券及暂停控制行先提交，再在排他锁内准备 Redis 和提交 READY。 */
    @Test
    void commitsPendingBeforeInitializingAndOpening() {
        service.addSeckillVoucher(voucher);
        var sequence = inOrder(vouchers, campaigns, manager, store);
        sequence.verify(vouchers).save(any());
        sequence.verify(campaigns).insertPending(30L);
        sequence.verify(manager).commit(any());
        sequence.verify(campaigns).lockExclusive(30L);
        sequence.verify(store).initializeCampaign(30L, 1L, 10, begin, begin.plusDays(1), true);
        sequence.verify(campaigns).markReady(30L, 1L);
        sequence.verify(manager).commit(any());
        verifyNoInteractions(redis);
    }

    /** Redis 结果未知时回滚发布事务，异常保留已提交的新券编号。 */
    @Test
    void redisFailureKeepsVoucherIdAndNeverOpens() {
        doThrow(new IllegalStateException("timeout")).when(store).initializeCampaign(any(), any(), any(), any(), any(), anyBoolean());
        assertThatThrownBy(() -> service.addSeckillVoucher(voucher))
                .isInstanceOf(SeckillCampaignPublicationPendingException.class)
                .satisfies(ex -> assertThat(((SeckillCampaignPublicationPendingException) ex).getVoucherId()).isEqualTo(30L));
        verify(manager).commit(any());
        verify(manager).rollback(any());
        verify(campaigns, never()).markReady(any(), any());
    }

    /** 已发布的活动只能核对已有 Redis，不能再次允许创建库存。 */
    @Test
    void publishedReplayOnlyVerifies() {
        campaign.setStatus("READY"); campaign.setPublicationStatus("PUBLISHED");
        service.initializeSeckillCampaign(30L);
        verify(store).initializeCampaign(30L, 1L, 10, begin, begin.plusDays(1), false);
        verify(campaigns, never()).markReady(any(), any());
        verify(service, never()).save(any(Voucher.class));
    }

    /** 历史活动和已发布后被暂停的活动不能通过首次发布入口重新开放。 */
    @Test
    void unmanagedAndPublishedPausedRemainUntouched() {
        campaign.setPublicationStatus("UNMANAGED");
        assertThatThrownBy(() -> service.initializeSeckillCampaign(30L)).isInstanceOf(IllegalStateException.class);
        campaign.setPublicationStatus("PUBLISHED");
        assertThatThrownBy(() -> service.initializeSeckillCampaign(30L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }

    /** 首次数据库写入失败时，不进入 Redis 发布流程。 */
    @Test
    void databaseFailureRollsBackWithoutInitializing() {
        when(vouchers.save(any())).thenReturn(false);
        assertThatThrownBy(() -> service.addSeckillVoucher(voucher)).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
        verifyNoInteractions(store, campaigns);
    }

    /** 可靠组件关闭时仍只保存旧库存键，重试新链路入口则拒绝执行。 */
    @Test
    @SuppressWarnings("unchecked")
    void disabledUsesLegacyPathOnly() {
        properties.setReliableComponentsEnabled(false);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service.addSeckillVoucher(voucher);
        verify(values).set("hmdp:seckill:stock:30", "10");
        verifyNoInteractions(store, campaigns, provider);
        assertThatThrownBy(() -> service.initializeSeckillCampaign(30L)).isInstanceOf(IllegalStateException.class);
    }

    /** 配置开启但 Redis 组件不存在时，在建券前失败，不能悄悄退回旧链路。 */
    @Test
    void missingComponentDoesNotCreateVoucher() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.addSeckillVoucher(voucher)).isInstanceOf(IllegalStateException.class);
        verify(service, never()).save(any(Voucher.class));
        verifyNoInteractions(manager, campaigns, redis);
    }

    /** 非法输入在任何数据库及 Redis 写入前拒绝。 */
    @Test
    void rejectsInvalidVoucherBeforeWriting() {
        voucher.setId(30L);
        assertThatThrownBy(() -> service.addSeckillVoucher(voucher)).isInstanceOf(IllegalArgumentException.class);
        voucher.setId(null).setStock(-1);
        assertThatThrownBy(() -> service.addSeckillVoucher(voucher)).isInstanceOf(IllegalArgumentException.class);
        voucher.setStock(10).setEndTime(begin);
        assertThatThrownBy(() -> service.addSeckillVoucher(voucher)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(manager, store, campaigns);
    }

    /** 外层事务不能包裹活动发布，避免形成误导性的事务边界。 */
    @Test
    void rejectsOuterTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.addSeckillVoucher(voucher)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.initializeSeckillCampaign(30L)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(manager, store);
    }

    /** 中断发生在 Redis 返回后时不开放活动，并保留中断标记。 */
    @Test
    void interruptionAfterRedisDoesNotOpen() {
        doAnswer(call -> { Thread.currentThread().interrupt(); return null; }).when(store)
                .initializeCampaign(any(), any(), any(), any(), any(), anyBoolean());
        try {
            assertThatThrownBy(() -> service.initializeSeckillCampaign(30L)).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(campaigns, never()).markReady(any(), any());
        verify(manager).rollback(any());
    }
}
