package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.constants.SeckillRequestStatus;
import com.hmdp.constants.SeckillReservationResult;
import com.hmdp.dto.SeckillAcceptance;
import com.hmdp.entity.MqOutbox;
import com.hmdp.entity.SeckillCampaignControl;
import com.hmdp.entity.SeckillRequest;
import com.hmdp.exception.SeckillAcceptancePendingException;
import com.hmdp.infrastructure.mq.SeckillOrderMessage;
import com.hmdp.infrastructure.redis.RedisIdGenerator;
import com.hmdp.infrastructure.seckill.SeckillReservationStore;
import com.hmdp.mapper.MqOutboxMapper;
import com.hmdp.mapper.SeckillCampaignControlMapper;
import com.hmdp.mapper.SeckillRequestMapper;
import com.hmdp.service.ISeckillRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

import static com.hmdp.constants.SeckillConstants.CREATE_ORDER_EVENT_TYPE;

/**
 * 可靠受理单独负责两个数据库事务及 Redis 预占，不发送 MQ、不创建订单。
 * 与旧 Stream 服务分开，避免未完成迁移时混用两种库存命名空间。
 *
 * @author myl
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "hmdp.seckill", name = "reliable-components-enabled", havingValue = "true")
public class SeckillRequestServiceImpl implements ISeckillRequestService {
    private final SeckillRequestMapper requests;
    private final SeckillCampaignControlMapper campaigns;
    private final MqOutboxMapper outbox;
    private final SeckillReservationStore reservations;
    private final RedisIdGenerator ids;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final SeckillOrderProperties properties;

    /**
     * 受理秒杀请求，确保订单创建前的数据库操作一致性和幂等性。
     *
     * @param userId    请求所属用户
     * @param voucherId 券编号
     * @param requestId 请求编号
     * @param traceId   跟踪编号
     * @return 受理结果
     */
    @Override
    public SeckillAcceptance accept(Long userId, Long voucherId, String requestId, String traceId) {
        // 验证输入参数
        validateInput(userId, voucherId, requestId, traceId);
        // 验证配置参数
        properties.validate();
        // 生成首次请求参数摘要
        String hash = payloadHash(userId, voucherId);
        // 尝试从数据库读取请求账本
        SeckillRequest request = requests.findByRequest(userId, requestId);
        // 如果请求账本不存在则创建
        if (request == null) {
            SeckillRequest candidate = new SeckillRequest();
            candidate.setOrderId(ids.getNextId("voucher_order"));
            candidate.setUserId(userId);
            candidate.setVoucherId(voucherId);
            candidate.setRequestId(requestId);
            candidate.setTraceId(traceId);
            candidate.setPayloadHash(hash);
            // 必须独立提交，后续 Redis 已扣但事务回滚时仍有可恢复记录。
            request = transaction().execute(status -> {
                requests.insertPreparing(candidate);
                return Objects.requireNonNull(requests.findByRequest(userId, requestId), "请求编号冲突");
            });
        }
        // 到这里请求账本必定存在，需验证状态
        validateBinding(request, userId, voucherId, hash);
        // 如果请求账本状态不是 PREPARING 则返回结果
        if (request.getStatus() != SeckillRequestStatus.PREPARING) {
            return result(request);
        }
        Long orderId = request.getOrderId();
        try {
            // execute 返回之前已提交；提交失败绝不向调用方返回 ACCEPTED。
            return transaction().execute(status -> acceptLocked(orderId, userId, voucherId, hash));
        } catch (RuntimeException ex) {
            log.debug("秒杀受理待确认，orderId={}，保留账本并沿用原请求重试", orderId, ex);
            throw new SeckillAcceptancePendingException(orderId, ex);
        }
    }

    /**
     * 在同一个短事务中先锁活动、再锁请求，执行 Lua 后同时提交 ACCEPTED 和 Outbox。
     *
     * @param orderId   稳定订单编号
     * @param userId    请求所属用户
     * @param voucherId 券编号
     * @param hash      首次请求参数摘要
     * @return 当前受理结果，需等待外层事务提交后才能返回给调用方
     */
    private SeckillAcceptance acceptLocked(Long orderId, Long userId, Long voucherId, String hash) {
        // 锁定活动
        SeckillCampaignControl campaign = campaigns.lockShared(voucherId);
        // 锁定请求
        SeckillRequest request = Objects.requireNonNull(requests.lockByOrderId(orderId), "请求账本缺失");
        // 验证请求账本状态
        validateBinding(request, userId, voucherId, hash);
        // 如果请求账本状态不是 PREPARING 则返回结果
        if (request.getStatus() != SeckillRequestStatus.PREPARING) {
            return result(request);
        }
        // 如果活动控制未就绪则抛出异常
        if (campaign == null || !"READY".equals(campaign.getStatus())) {
            throw new IllegalStateException("活动控制未就绪，保留请求等待恢复");
        }
        // 尝试预占库存
        SeckillReservationResult reserved = reservations.reserve(voucherId, userId, orderId, campaign.getEpoch());
        // 如果预占结果是 NOT_READY、TERMINAL 或 CONFLICT 则抛出异常
        if (reserved == SeckillReservationResult.NOT_READY || reserved == SeckillReservationResult.TERMINAL
                || reserved == SeckillReservationResult.CONFLICT) {
            throw new IllegalStateException("预占与请求账本不一致，需恢复任务核对");
        }
        // 如果预占结果不是 RESERVED 则标记请求账本为 REJECTED 并返回结果
        if (reserved != SeckillReservationResult.RESERVED) {
            requireOne(requests.markRejected(orderId, reserved.name()));
            return new SeckillAcceptance(orderId, SeckillRequestStatus.REJECTED, reserved.name());
        }
        // 设定预占代次
        request.setReservationEpoch(campaign.getEpoch());
        // 设定受理时间，精确到毫秒
        request.setAcceptedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        // 标记请求账本为 ACCEPTED
        requireOne(requests.markAccepted(request));
        // 登记订单事件
        requireOne(outbox.insertEvent(createEvent(request)));
        log.debug("秒杀受理已登记待提交，orderId={}，epoch={}", orderId, campaign.getEpoch());
        return new SeckillAcceptance(orderId, SeckillRequestStatus.ACCEPTED, null);
    }

    /**
     * 生成稳定的订单事件，消息体随 Outbox 一起持久化。
     *
     * @param request 已确定预占代次和受理时间的请求
     * @return CREATE_ORDER 待发记录
     * @throws IllegalStateException 消息序列化失败时抛出并回滚受理事务
     */
    private MqOutbox createEvent(SeckillRequest request) {
        SeckillOrderMessage message = new SeckillOrderMessage(
                SeckillOrderMessage.CURRENT_SCHEMA_VERSION,
                "order-" + request.getOrderId(),
                request.getOrderId(),
                request.getUserId(),
                request.getVoucherId(),
                request.getTraceId(),
                request.getAcceptedAt());
        MqOutbox event = new MqOutbox();
        event.setEventId(message.eventId());
        event.setOrderId(message.orderId());
        event.setEventType(CREATE_ORDER_EVENT_TYPE);
        event.setSchemaVersion(message.schemaVersion());
        try {
            // 设置消息体
            event.setPayload(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("订单事件序列化失败", ex);
        }
        return event;
    }

    /**
     * 创建固定超时的独立事务，避免自调用代理失效及外层事务延迟提交。
     *
     * @return READ_COMMITTED、REQUIRES_NEW 的事务模板
     */
    private TransactionTemplate transaction() {
        // 创建事务模板
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // 设置事务传播行为为 REQUIRES_NEW 无论有没有事务，都创建新事务
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 设置事务隔离级别为 READ_COMMITTED 读已提交
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // 设置事务超时时间为配置参数
        template.setTimeout(properties.getTransactionTimeoutSeconds());
        return template;
    }

    /**
     * 校验受理参数，不允许非法标识进入 Lua 或数据库。
     *
     * @param userId    用户编号
     * @param voucherId 券编号
     * @param requestId 重试标识
     * @param traceId   跟踪标识
     * @throws IllegalArgumentException 参数不符合约定时抛出
     */
    private void validateInput(Long userId, Long voucherId, String requestId, String traceId) {
        if (userId == null || userId < 1 || voucherId == null || voucherId < 1
                || requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,128}")
                || !StringUtils.hasText(traceId) || traceId.length() > 128) {
            throw new IllegalArgumentException("用户、券编号或请求跟踪标识非法");
        }
    }

    /**
     * 计算当前业务参数的固定摘要，数量固定为 1。
     *
     * @param userId    用户编号
     * @param voucherId 券编号
     * @return SHA-256 十六进制摘要
     */
    private String payloadHash(Long userId, Long voucherId) {
        try {
            byte[] payload = (userId + ":" + voucherId + ":1").getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Java 运行环境不支持 SHA-256", ex);
        }
    }

    /**
     * 防止同一 requestId 被用于不同业务参数。
     *
     * @param request   原请求
     * @param userId    本次用户编号
     * @param voucherId 本次券编号
     * @param hash      本次业务摘要
     * @throws IllegalArgumentException 参数与首次请求不一致时抛出
     */
    private void validateBinding(SeckillRequest request, Long userId, Long voucherId, String hash) {
        if (!Objects.equals(request.getUserId(), userId) || !Objects.equals(request.getVoucherId(), voucherId)
                || !Objects.equals(request.getPayloadHash(), hash)) {
            throw new IllegalArgumentException("requestId 已绑定其他业务参数");
        }
    }

    /**
     * 转换已持久化的请求状态。
     *
     * @param request 已有账本
     * @return 原订单编号和处理状态
     */
    private SeckillAcceptance result(SeckillRequest request) {
        return new SeckillAcceptance(request.getOrderId(), request.getStatus(), request.getFailureReason());
    }

    /**
     * 禁止无效更新被当成成功受理。
     *
     * @param affected SQL 影响行数
     * @throws IllegalStateException 影响行数不为 1 时抛出并回滚
     */
    private void requireOne(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("账本或 Outbox 更新行数异常");
        }
    }
}
