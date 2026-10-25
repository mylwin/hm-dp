package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.constants.SeckillOrderFailureReason;
import com.hmdp.constants.SeckillRequestStatus;
import com.hmdp.entity.MqOutbox;
import com.hmdp.entity.SeckillCampaignControl;
import com.hmdp.entity.SeckillRequest;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.OrderCreationRejectedException;
import com.hmdp.infrastructure.mq.SeckillOrderMessage;
import com.hmdp.mapper.*;
import com.hmdp.service.ISeckillOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

import static com.hmdp.constants.SeckillConstants.*;

/**
 * 负责新链路建单、重复消息核对及预占任务登记，不调用旧 Stream 服务。
 *
 * @author myl
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "hmdp.seckill", name = "reliable-components-enabled", havingValue = "true")
public class SeckillOrderServiceImpl implements ISeckillOrderService {
    private final SeckillCampaignControlMapper campaigns;
    private final SeckillRequestMapper requests;
    private final VoucherOrderMapper orders;
    private final SeckillVoucherMapper vouchers;
    private final MqOutboxMapper outbox;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final SeckillOrderProperties properties;

    /**
     * {@inheritDoc}
     */
    @Override
    public void verifySchema() {
        if (orders.countUserVoucherUniqueIndexes() < 1) {
            throw new IllegalStateException("请先核查重复订单并执行第三阶段唯一索引迁移");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createFromMessage(SeckillOrderMessage message) {
        Objects.requireNonNull(message, "订单消息不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("订单消费不能从已有事务内调用");
        }
        properties.validate();
        try {
            // 创建订单
            transaction().executeWithoutResult(status -> createLocked(message));
        } catch (OrderCreationRejectedException ex) {
            // 创建订单出现订单创建拒绝异常，关闭请求
            transaction().executeWithoutResult(status -> closeLocked(message, ex.getReason(), null));
        } catch (DuplicateKeyException ex) {
            // 原事务已回滚，再读已提交订单；不能把所有重复键都当作业务重复。
            transaction().executeWithoutResult(status -> closeLocked(
                    message, SeckillOrderFailureReason.ORDER_CONFLICT, ex));
        }
        log.debug("秒杀消息处理已提交，orderId={}，eventId={}", message.orderId(), message.eventId());
    }

    /**
     * 仅对 ACCEPTED 扣库存并插单，成功账本和确认任务一起提交。
     *
     * @param message 建单消息
     */
    private void createLocked(SeckillOrderMessage message) {
        SeckillRequest request = lockRequest(message);
        // 如果请求已终态则核对订单
        if (isTerminal(request)) {
            verifySucceededOrder(request);
            return;
        }
        // 到这里请求必然是已受理状态
        requireAccepted(request);
        // 获取已存在的订单
        VoucherOrder existing = orders.findOrder(message.orderId());
        // 如果存在订单则核对订单
        if (existing != null) {
            requireMatchingOrder(existing, request);
        } else {
            // 如果用户已下过该券订单则拒绝
            if (orders.findUserVoucherOrder(request.getUserId(), request.getVoucherId()) != null) {
                throw new OrderCreationRejectedException(SeckillOrderFailureReason.ORDER_CONFLICT);
            }
            // 如果库存不足则拒绝
            if (vouchers.deductOne(request.getVoucherId()) != 1) {
                throw new OrderCreationRejectedException(SeckillOrderFailureReason.DB_STOCK_INSUFFICIENT);
            }
            // 创建订单
            VoucherOrder order = new VoucherOrder().setId(request.getOrderId())
                    .setUserId(request.getUserId()).setVoucherId(request.getVoucherId());
            // 检查插入结果
            requireOne(orders.insertSeckillOrder(order));
        }
        succeed(request);
    }

    /**
     * 原建单事务回滚后重新核对，只关闭仍未建单的已受理请求。
     *
     * @param message   建单消息
     * @param reason    已确认的业务失败原因
     * @param duplicate 重复键异常，非重复键分支为 null
     */
    private void closeLocked(SeckillOrderMessage message, SeckillOrderFailureReason reason,
                             DuplicateKeyException duplicate) {
        SeckillRequest request = lockRequest(message);
        // 如果请求已终态则核对订单
        if (isTerminal(request)) {
            verifySucceededOrder(request);
            return;
        }
        requireAccepted(request);
        // 回滚到重新加锁之间，其他消费者可能已完成建单。
        VoucherOrder own = orders.findOrder(request.getOrderId());
        // 如果存在订单则核对订单
        if (own != null) {
            requireMatchingOrder(own, request);
            succeed(request);
            return;
        }
        // 如果重复键不为 null 且用户无该券订单则抛出重复键异常
        if (duplicate != null
                && orders.findUserVoucherOrder(request.getUserId(), request.getVoucherId()) == null) {
            throw duplicate;
        }
        requireOne(requests.markClosed(request.getOrderId(), reason.name()));
        insertReservationTask(request, RELEASE_RESERVATION_EVENT_TYPE, "release-");
    }

    /**
     * 保存成功状态和确认任务，不再次减少 Redis 库存。
     *
     * @param request 已锁定的受理请求
     */
    private void succeed(SeckillRequest request) {
        requireOne(requests.markSucceeded(request.getOrderId()));
        insertReservationTask(request, CONFIRM_RESERVATION_EVENT_TYPE, "confirm-");
    }

    /**
     * 按活动、请求的固定顺序加锁，核对消息身份和活动代次。
     *
     * @param message 建单消息
     * @return 身份一致的已锁定请求
     * @throws IllegalArgumentException 消息与账本不符
     * @throws IllegalStateException    数据缺失或活动正在维护
     */
    private SeckillRequest lockRequest(SeckillOrderMessage message) {
        // 活动加共享锁
        SeckillCampaignControl campaign = campaigns.lockShared(message.voucherId());
        // 请求加排他锁
        SeckillRequest request = requests.lockByOrderId(message.orderId());
        if (campaign == null || request == null) {
            throw new IllegalStateException("活动或请求账本缺失，等待核对");
        }
        if (!Objects.equals(request.getUserId(), message.userId())
                || !Objects.equals(request.getVoucherId(), message.voucherId())
                || !Objects.equals("order-" + request.getOrderId(), message.eventId())
                || !Objects.equals(request.getTraceId(), message.traceId())
                || (request.getAcceptedAt() != null && !Objects.equals(request.getAcceptedAt(), message.acceptedAt()))) {
            throw new IllegalArgumentException("订单消息与受理账本不一致");
        }
        // 已受理请求遇到活动维护或代次不一致时抛出异常，等待重试核对。
        if (request.getStatus() == SeckillRequestStatus.ACCEPTED
                && (!"READY".equals(campaign.getStatus())
                || !Objects.equals(campaign.getEpoch(), request.getReservationEpoch()))) {
            throw new IllegalStateException("活动维护中或预占代次不一致，等待核对");
        }
        return request;
    }

    /**
     * 判断请求是否已结束，终态不再扣库存。
     *
     * @param request 已锁定请求
     * @return 成功、拒绝或关闭时为 true
     */
    private boolean isTerminal(SeckillRequest request) {
        return request.getStatus() == SeckillRequestStatus.SUCCEEDED
                || request.getStatus() == SeckillRequestStatus.REJECTED
                || request.getStatus() == SeckillRequestStatus.CLOSED;
    }

    /**
     * 核对成功账本对应的订单，避免掩盖数据丢失。
     *
     * @param request 已锁定请求
     */
    private void verifySucceededOrder(SeckillRequest request) {
        if (request.getStatus() == SeckillRequestStatus.SUCCEEDED) {
            requireMatchingOrder(orders.findOrder(request.getOrderId()), request);
        }
    }

    /**
     * 阻止未受理请求被消息直接推进。
     *
     * @param request 请求账本
     * @throws IllegalStateException 请求尚未完整受理
     */
    private void requireAccepted(SeckillRequest request) {
        if (request.getStatus() != SeckillRequestStatus.ACCEPTED || request.getAcceptedAt() == null
                || request.getReservationEpoch() == null) {
            throw new IllegalStateException("请求尚未完整受理，等待恢复");
        }
    }

    /**
     * 核对订单编号、用户和券，拒绝编号碰撞。
     *
     * @param order   已有订单，允许为 null
     * @param request 请求账本
     * @throws IllegalStateException 订单不存在或归属不符
     */
    private void requireMatchingOrder(VoucherOrder order, SeckillRequest request) {
        if (order == null || !Objects.equals(order.getId(), request.getOrderId())
                || !Objects.equals(order.getUserId(), request.getUserId())
                || !Objects.equals(order.getVoucherId(), request.getVoucherId())) {
            throw new IllegalStateException("订单事实与请求账本不一致");
        }
    }

    /**
     * 登记确认或释放任务，当前 MQ 投递器不会领取这两类记录。
     *
     * @param request 已锁定的受理请求
     * @param type    任务类型
     * @param prefix  稳定事件编号前缀
     */
    private void insertReservationTask(SeckillRequest request, String type, String prefix) {
        SeckillOrderMessage task = new SeckillOrderMessage(SeckillOrderMessage.CURRENT_SCHEMA_VERSION,
                prefix + request.getOrderId(), request.getOrderId(), request.getUserId(), request.getVoucherId(),
                request.getTraceId(), request.getAcceptedAt());
        MqOutbox event = new MqOutbox();
        event.setEventId(task.eventId());
        event.setOrderId(task.orderId());
        event.setEventType(type);
        event.setSchemaVersion(task.schemaVersion());
        try {
            event.setPayload(objectMapper.writeValueAsString(task));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("预占任务序列化失败", ex);
        }
        requireOne(outbox.insertEvent(event));
    }

    /**
     * 创建独立短事务，提交后才允许确认 MQ 消费。
     *
     * @return 读提交、独立传播的事务模板
     */
    private TransactionTemplate transaction() {
        // 创建一个事务模板，设置为新事务，读提交隔离，超时 5 秒
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(properties.getTransactionTimeoutSeconds());
        return template;
    }

    /**
     * 拒绝静默失败的数据库写入。
     *
     * @param affected 实际影响行数
     * @throws IllegalStateException 未恰好写入一行
     */
    private void requireOne(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("订单、账本或预占任务写入失败");
        }
    }
}
