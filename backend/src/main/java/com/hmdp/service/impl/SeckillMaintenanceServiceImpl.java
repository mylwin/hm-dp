package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillMaintenanceProperties;
import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.constants.SeckillRequestStatus;
import com.hmdp.entity.*;
import com.hmdp.exception.SeckillConsistencyException;
import com.hmdp.infrastructure.mq.SeckillOrderMessage;
import com.hmdp.infrastructure.seckill.SeckillReservationStore;
import com.hmdp.mapper.*;
import com.hmdp.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.hmdp.constants.SeckillConstants.*;

/**
 * 给秒杀请求做后台收尾和故障恢复：确认成功预占、释放关闭预占、继续发布新券及恢复未完成请求。
 * 同时提供原事件重投、关闭未建单请求和隔离消息处置，所有写操作保留审计；不调用旧 Stream。
 *
 * @author myl
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "hmdp.seckill", name = "reliable-components-enabled", havingValue = "true")
public class SeckillMaintenanceServiceImpl implements ISeckillMaintenanceService {
    private final SeckillMaintenanceMapper maintenance;
    private final SeckillCampaignControlMapper campaigns;
    private final SeckillRequestMapper requests;
    private final VoucherOrderMapper orders;
    private final MqOutboxMapper outbox;
    private final SeckillReservationStore reservations;
    private final ISeckillRequestService acceptance;
    private final IVoucherService vouchers;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactionManager;
    private final SeckillOrderProperties orderProperties;
    private final SeckillMaintenanceProperties properties;

    /**
     * 执行数据库中待处理的预占确认和释放任务。
     * 成功订单的预占改为 CONFIRMED，不再扣库存；已关闭且未建单的请求释放预占，最多回补一份。
     * 每轮最多领取配置的批量数。失败时尝试登记下次执行时间；失去租约后不覆盖他人状态，未确认任务可到期接管。
     *
     * @return 本轮领取的任务数；0 表示未领到任务，计数包含执行失败的任务
     * @throws IllegalStateException 调用方已有事务或入口线程已中断
     */
    @Override
    public int processReservationTasks() {
        requireStandalone();
        int processed = 0;
        // 每轮只领取配置数量；线程中断后不再领取下一条，已经领到的任务仍受租约保护。
        while (processed < properties.getBatchSize() && !Thread.currentThread().isInterrupted()) {
            MqOutbox event = transaction().execute(status -> {
                MqOutbox task = maintenance.lockNextReservationTask();
                if (task == null) {
                    return null;
                }
                String token = UUID.randomUUID().toString();
                requireOne(outbox.claim(task.getEventId(), token, properties.getLeaseMs()));
                task.setLeaseToken(token);
                task.setAttempts(task.getAttempts() == Integer.MAX_VALUE ? Integer.MAX_VALUE : task.getAttempts() + 1);
                return task;
            });
            // 如果没有任务则退出循环
            if (event == null) {
                break;
            }
            processed++;
            try {
                // 核对 JSON 正文与 Outbox 表中的事件号、订单号和版本，防止串单。
                SeckillOrderMessage message = decode(event);
                // 在新的短事务里检查请求终态和原订单，再确认或释放 Redis 预占。
                transaction().executeWithoutResult(status -> finishLocked(event, message));
            } catch (RuntimeException ex) {
                // 这里只登记下次执行时间，不当场重试；已写入 Redis 的结果由后续幂等调用核对。
                transaction().executeWithoutResult(status -> outbox.retryLater(event.getEventId(),
                        event.getLeaseToken(), retryDelay(event.getAttempts()), ex.getClass().getSimpleName()));
                // 发现已知数据冲突时检查代次，决定是否暂停对应活动；不把请求直接改成 CLOSED。
                if (ex instanceof SeckillConsistencyException) {
                    pauseConflict(event.getOrderId(), ex);
                }
                log.warn("SECKILL_TASK_RETRY eventId={} attempts={} errorType={}", event.getEventId(),
                        event.getAttempts(), ex.getClass().getSimpleName());
            }
        }
        return processed;
    }

    /**
     * 在当前事务中确认成功订单的预占，或释放已关闭且未建单请求的预占。
     * 先取活动共享锁、请求排他锁和任务排他锁，再检查消息身份、代次及租约。
     * 租约已失效时直接返回；资格有效才执行 Redis 并登记任务 SENT，数据库锁持有到外层提交或回滚。
     *
     * @param event   本轮已领取临时处理资格的确认或释放任务，含当前租约令牌
     * @param message 从任务正文解析出的原订单、用户、券、跟踪号及受理时间
     * @throws SeckillConsistencyException 任务与请求不符、代次不同、确认缺原订单或释放时原订单已存在
     * @throws IllegalStateException       请求不存在或执行期间线程中断
     */
    private void finishLocked(MqOutbox event, SeckillOrderMessage message) {
        // 锁定活动
        SeckillCampaignControl campaign = campaigns.lockShared(message.voucherId());
        // 锁定请求
        SeckillRequest request = requireRequest(requests.lockByOrderId(message.orderId()));
        // 事件前缀、订单号、用户、券、跟踪号和受理时间都必须与当前请求相同。
        verifyMessage(request, message, event.getEventType());
        // 旧库存版本的任务不能操作当前版本的 Redis 库存。
        requireEpoch(campaign, request);
        // 锁住事件
        MqOutbox current = maintenance.lockEvent(event.getEventId());
        // 租约令牌已被替换或已到期时直接退出，本线程不再执行 Redis。
        if (current == null || !Objects.equals(current.getLeaseToken(), event.getLeaseToken())
                || maintenance.validLease(event.getEventId(), event.getLeaseToken()) != 1) {
            return;
        }
        // 验证事件类型
        boolean confirm = CONFIRM_RESERVATION_EVENT_TYPE.equals(event.getEventType());
        VoucherOrder order = orders.findOrder(request.getOrderId());
        // 只有 SUCCEEDED 且原订单归属一致才允许确认；释放则要求 CLOSED 且原订单不存在。
        if (confirm) {
            if (request.getStatus() != SeckillRequestStatus.SUCCEEDED || !matching(order, request)) {
                throw new SeckillConsistencyException("确认任务没有成功账本及匹配订单");
            }
        } else if (request.getStatus() != SeckillRequestStatus.CLOSED || order != null) {
            throw new SeckillConsistencyException("释放任务没有关闭账本或原订单已存在");
        }
        requireUninterrupted();
        // 确认不再扣库；释放最多回补一次；原 PREPARING 被关闭时才允许缺记录写零数量墓碑。
        reservations.finish(request.getVoucherId(), request.getUserId(), request.getOrderId(),
                request.getReservationEpoch(), confirm, "OPERATOR_CLOSED_PREPARING".equals(request.getFailureReason()));
        requireUninterrupted();
        // SENT 对本地任务表示 Redis 已执行；若租约过期，保留待办，下一轮幂等重试。
        outbox.markSent(event.getEventId(), event.getLeaseToken(), confirm ? "redis-confirmed" : "redis-released");
    }

    /**
     * 继续发布尚未开放的新券，并检查长期停在 PREPARING 或 ACCEPTED 的请求。
     * PREPARING 沿用原请求继续受理；ACCEPTED 核对订单及预占后恢复原建单事件的投递。
     * 活动和请求各最多检查一个配置批次，失败后延迟再查，不因为等待过久自动关闭请求。
     *
     * @return 本轮领取恢复资格的活动数与请求数之和；0 表示没有领取，不代表所有待办均已完成
     * @throws IllegalStateException 调用方已有事务或入口线程已中断
     */
    @Override
    public int recoverDue() {
        requireStandalone();
        int processed = 0;
        // 遍历待恢复的活动
        for (Long voucherId : maintenance.pendingCampaigns(properties.getBatchSize())) {
            // 如果线程中断则退出循环
            if (Thread.currentThread().isInterrupted()) {
                return processed;
            }
            // 如果没有取得本轮恢复资格则继续下一次循环
            if (!claimRecovery("CAMPAIGN", voucherId)) {
                continue;
            }
            processed++;
            try {
                // 初始化券发布
                vouchers.initializeSeckillCampaign(voucherId);
            } catch (RuntimeException ex) {
                // 如果初始化券异常，则登记失败数据
                maintenance.attemptResult("CAMPAIGN", voucherId, ex.getClass().getSimpleName());
                // 如果是数据冲突异常，则登记暂停状态并提交审核
                if (ex instanceof SeckillConsistencyException) {
                    transaction().executeWithoutResult(status -> {
                        SeckillCampaignControl campaign = campaigns.lockExclusive(voucherId);
                        if (campaign != null && "PENDING".equals(campaign.getPublicationStatus())) {
                            maintenance.pause(voucherId, campaign.getEpoch());
                            requireOne(maintenance.audit(null, voucherId, "CONSISTENCY_CONFLICT", "SYSTEM", conflictReason(ex)));
                        }
                    });
                }
                log.warn("SECKILL_CAMPAIGN_RECOVERY voucherId={} errorType={}", voucherId, ex.getClass().getSimpleName());
            }
        }
        // 遍历长时间停留在 PREPARING 或 ACCEPTED 的请求
        for (SeckillRequest candidate : maintenance.staleRequests(properties.getStaleSeconds(), properties.getBatchSize())) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            // 如果没有取得本轮恢复资格则继续下一次循环
            if (!claimRecovery("REQUEST", candidate.getOrderId())) {
                continue;
            }
            processed++;
            try {
                // 如果请求状态为 PREPARING
                if (candidate.getStatus() == SeckillRequestStatus.PREPARING) {
                    // 如果原订单已存在则抛出异常
                    if (orders.findOrder(candidate.getOrderId()) != null) {
                        throw new SeckillConsistencyException("PREPARING 已有原订单，禁止重新预占");
                    }
                    // accept 会重新锁活动和请求，并复用原请求、原订单及原 traceId。
                    acceptance.accept(candidate.getUserId(), candidate.getVoucherId(), candidate.getRequestId(), candidate.getTraceId());
                } else {
                    // 如果
                    replay(candidate.getOrderId(), null, "SYSTEM", "长期 ACCEPTED 事实核对与原事件恢复");
                }
            } catch (RuntimeException ex) {
                maintenance.attemptResult("REQUEST", candidate.getOrderId(), ex.getClass().getSimpleName());
                if (candidate.getStatus() == SeckillRequestStatus.PREPARING && (ex instanceof SeckillConsistencyException
                        || ex.getCause() instanceof SeckillConsistencyException)) {
                    pauseConflict(candidate.getOrderId(), ex);
                }
                log.warn("SECKILL_REQUEST_RECOVERY orderId={} errorType={}", candidate.getOrderId(), ex.getClass().getSimpleName());
            }
        }
        return processed;
    }

    /**
     * 为指定活动或请求登记本轮恢复尝试，并写入下一次允许检查的时间。
     * 只有成功更新到期记录的实例才继续处理；进程退出后，其他实例可以在下次到期时重新领取。
     *
     * @param type CAMPAIGN 表示恢复新券发布，REQUEST 表示恢复抢购请求
     * @param id   CAMPAIGN 对应券编号，REQUEST 对应原订单编号
     * @return 本实例已取得本轮恢复资格时为 true；其他实例已领取或仍在等待下次执行时为 false
     */
    private boolean claimRecovery(String type, Long id) {
        return Boolean.TRUE.equals(transaction().execute(status -> {
            maintenance.ensureAttempt(type, id);
            return maintenance.claimAttempt(type, id, properties.getRecoveryRetrySeconds()) == 1;
        }));
    }

    /**
     * 让原订单的建单消息重新进入待投递状态，不创建新订单编号或替换消息正文。
     * 仅处理活动已开放、代次一致且预占有效的 ACCEPTED 请求，并核对原订单和原消息身份。
     * 人工操作不能抢占有效发送租约；SYSTEM 自动恢复只唤醒 SENT，其他状态交原投递器处理。
     * 实际重投会登记审计；关联隔离消息时，其原文也必须与账本一致，处置和重投一起提交。
     *
     * @param orderId      原请求绑定的正数订单编号
     * @param quarantineId 待关联的隔离编号；null 表示不处理隔离记录
     * @param operator     当前登录操作者编号；后台自动恢复使用 SYSTEM，长度 1 到 128 字符
     * @param reason       重投原因，非空白且长度为 1 到 256 字符
     * @throws IllegalArgumentException 订单编号、操作者或原因不符合约定
     * @throws IllegalStateException    已有外层事务、线程中断、状态不允许重投、消息不符或发送租约仍有效
     */
    @Override
    public void replay(Long orderId, String quarantineId, String operator, String reason) {
        requireStandalone();
        validateOperation(orderId, operator, reason);
        SeckillRequest snapshot = requireRequest(maintenance.findRequest(orderId));
        try {
            transaction().executeWithoutResult(status -> replayLocked(snapshot, quarantineId, operator, reason));
        } catch (SeckillConsistencyException ex) {
            pauseConflict(orderId, ex);
            throw ex;
        }
    }

    /**
     * 在当前事务中把原建单事件补建或重新设为 PENDING，并保存重投审计。
     * 重新锁定请求后，核对 ACCEPTED、活动 READY、代次、订单归属和 Redis 预占，再核对原 Outbox 正文。
     * SYSTEM 遇到已有 PENDING/SENDING 直接返回；人工操作不能抢有效租约；隔离核对失败会回滚先前事件修改。
     *
     * @param snapshot     事务前读取的请求，仅用券编号和订单编号定位；业务判断使用锁内重新读取的请求
     * @param quarantineId 可选隔离编号；null 表示本次不关联隔离消息
     * @param operator     可信登录操作者编号，或后台自动恢复标识 SYSTEM
     * @param reason       已通过长度及非空白校验的重投依据
     * @throws SeckillConsistencyException 代次、订单归属、预占或原 Outbox 正文与账本不一致
     * @throws IllegalStateException       请求非 ACCEPTED、活动暂停、发送租约有效或隔离记录不能用于本次重投
     */
    private void replayLocked(SeckillRequest snapshot, String quarantineId, String operator, String reason) {
        SeckillCampaignControl campaign = campaigns.lockShared(snapshot.getVoucherId());
        SeckillRequest request = requireRequest(requests.lockByOrderId(snapshot.getOrderId()));
        if (request.getStatus() != SeckillRequestStatus.ACCEPTED) {
            throw new IllegalStateException("仅 ACCEPTED 请求允许重投，终态不得重新建单");
        }
        requireEpoch(campaign, request);
        if (!"READY".equals(campaign.getStatus())) {
            throw new IllegalStateException("活动暂停中，先核对维护原因");
        }
        VoucherOrder order = orders.findOrder(request.getOrderId());
        if (order != null && !matching(order, request)) {
            throw new SeckillConsistencyException("原订单归属与请求不一致");
        }
        String reservation = reservations.verify(request.getVoucherId(), request.getUserId(), request.getOrderId(), request.getReservationEpoch());
        if ("CONFIRMED".equals(reservation) && order == null) {
            throw new SeckillConsistencyException("预占已确认但数据库订单不存在");
        }
        SeckillOrderMessage expected = message(request, CREATE_ORDER_EVENT_TYPE);
        MqOutbox event = maintenance.lockEvent(expected.eventId());
        if (event == null) {
            requireOne(outbox.insertEvent(event(request, CREATE_ORDER_EVENT_TYPE)));
        } else {
            if (!CREATE_ORDER_EVENT_TYPE.equals(event.getEventType()) || !expected.equals(decode(event))) {
                throw new SeckillConsistencyException("原 Outbox 与请求账本不一致，禁止替换消息");
            }
            // 自动恢复不抢占正常待发或发送中的任务，投递器仍负责重试和租约接管。
            if ("SYSTEM".equals(operator) && !"SENT".equals(event.getStatus())) {
                return;
            }
            requireOne(maintenance.replayEvent(event.getEventId()));
        }
        if (quarantineId != null) {
            QuarantinedOrderMessage isolated = maintenance.lockQuarantine(quarantineId);
            if (isolated == null || isolated.isResolved() || isolated.getBody() == null) {
                throw new IllegalStateException("隔离记录不存在、已处理或没有可核对原文");
            }
            try {
                if (!expected.equals(json.readValue(isolated.getBody(), SeckillOrderMessage.class))) {
                    throw new IllegalStateException("隔离消息与账本不一致，禁止替换订单身份");
                }
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("隔离消息无法解析，不能重投", ex);
            }
            requireOne(maintenance.resolveQuarantine(quarantineId, "REPLAY: " + reason.substring(0, Math.min(248, reason.length()))));
        }
        requireOne(maintenance.audit(request.getOrderId(), request.getVoucherId(), "REPLAY", operator, reason));
    }

    /**
     * 关闭尚未建单的抢购请求，并同时保存后续归还 Redis 预占的任务。
     * 仅允许活动 READY 且原订单不存在的 PREPARING、ACCEPTED 请求；已有订单或终态时拒绝关闭。
     * 关闭状态、释放任务和审计一起提交，Redis 库存由后台任务随后回补，当前方法不直接加库存。
     *
     * @param orderId  要关闭的原请求订单编号，必须为正数
     * @param operator 当前登录操作者编号，非空白且长度为 1 到 128 字符
     * @param reason   关闭依据，非空白且长度为 1 到 256 字符
     * @throws IllegalArgumentException 订单编号、操作者或原因不符合约定
     * @throws IllegalStateException    已有外层事务、线程中断、活动未开放、代次冲突、已有订单或请求已终态
     */
    @Override
    public void close(Long orderId, String operator, String reason) {
        requireStandalone();
        validateOperation(orderId, operator, reason);
        SeckillRequest snapshot = requireRequest(maintenance.findRequest(orderId));
        transaction().executeWithoutResult(status -> {
            SeckillCampaignControl campaign = campaigns.lockShared(snapshot.getVoucherId());
            SeckillRequest request = requireRequest(requests.lockByOrderId(orderId));
            if (request.getStatus() != SeckillRequestStatus.PREPARING && request.getStatus() != SeckillRequestStatus.ACCEPTED) {
                throw new IllegalStateException("请求已终态，不能关闭或回补");
            }
            if (campaign == null || !"READY".equals(campaign.getStatus())) {
                throw new IllegalStateException("活动未就绪或已暂停，不能绕过维护流程关闭");
            }
            if (orders.findOrder(orderId) != null) {
                throw new IllegalStateException("已有原订单，禁止关闭请求或释放库存");
            }
            if (request.getReservationEpoch() == null) {
                request.setReservationEpoch(campaign.getEpoch());
            }
            requireEpoch(campaign, request);
            if (request.getAcceptedAt() == null) {
                request.setAcceptedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            }
            request.setFailureReason(request.getStatus() == SeckillRequestStatus.PREPARING
                    ? "OPERATOR_CLOSED_PREPARING" : "OPERATOR_CLOSED");
            requireOne(maintenance.closeRequest(request));
            requireOne(outbox.insertEvent(event(request, RELEASE_RESERVATION_EVENT_TYPE)));
            requireOne(maintenance.audit(orderId, request.getVoucherId(), "CLOSE", operator, reason));
        });
    }

    /**
     * 把已人工核对、确定无需重投的隔离消息标为已处置，并保存操作者和处置依据。
     * 保留隔离原文及摘要，不改变抢购请求、订单或库存；记录不存在或已经处置时返回失败。
     *
     * @param quarantineId 隔离记录编号，必须为 64 位小写十六进制字符串
     * @param operator     当前登录操作者编号，非空白且长度为 1 到 128 字符
     * @param reason       无需重投的依据，非空白且长度为 1 到 256 字符
     * @throws IllegalArgumentException 编号、操作者或原因不符合约定
     * @throws IllegalStateException    已有外层事务、线程中断、隔离记录不存在或已经处置
     */
    @Override
    public void dismissQuarantine(String quarantineId, String operator, String reason) {
        requireStandalone();
        validateOperation(1L, operator, reason);
        if (quarantineId == null || !quarantineId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("隔离编号非法");
        }
        transaction().executeWithoutResult(status -> {
            if (maintenance.lockQuarantine(quarantineId) == null) {
                throw new IllegalStateException("隔离记录不存在");
            }
            requireOne(maintenance.resolveQuarantine(quarantineId, reason));
            requireOne(maintenance.audit(null, null, "DISMISS_QUARANTINE", operator, quarantineId + ": " + reason.substring(0, Math.min(190, reason.length()))));
        });
    }

    /**
     * 查询尚未完成的建单、确认、释放、活动发布及请求恢复情况，供排查积压和触发告警。
     * 统计来自数据库，不代表 Broker 的精确队列深度，也不返回完整消息或凭据。
     *
     * @return 指标名到数字的映射，包括待办数量、最老年龄秒数、最大尝试次数和错误数量；没有待办时为 0
     */
    @Override
    public Map<String, Object> health() {
        return maintenance.health();
    }

    /**
     * 列出最早仍未处置的非法消息和死信，帮助运维定位需要核对的消息。
     * 每次最多返回配置批量数，包含隔离编号、消息编号、摘要和错误类型，不包含原始正文。
     *
     * @return 未处置消息的摘要列表，没有待办时为空列表
     */
    @Override
    public List<Map<String, Object>> quarantinedMessages() {
        return maintenance.quarantineSummary(properties.getBatchSize());
    }

    /**
     * 发现请求与预占或订单数据冲突后，暂停该请求对应的同代次活动并留下核对依据。
     * 须在原处理事务结束后调用，再按活动排他锁、请求排他锁的顺序检查最新代次，避免锁升级死锁。
     * 请求代次未固定或仍等于活动代次才写 PAUSED/BLOCKED；旧代次只记审计，不暂停新活动。
     * 请求已不存在时只输出错误；数据库或锁操作失败时，不能认为暂停和审计已经完成。
     *
     * @param orderId 出现冲突的原请求订单编号
     * @param failure 已知一致性冲突，或包装该冲突的受理待确认异常
     * @throws IllegalStateException 重新锁定时请求已不存在
     */
    private void pauseConflict(Long orderId, RuntimeException failure) {
        SeckillRequest snapshot = maintenance.findRequest(orderId);
        // 如果秒杀请求不存在，则记录错误并返回
        if (snapshot == null) {
            log.error("SECKILL_CONSISTENCY requestMissing orderId={}", orderId);
            return;
        }
        transaction().executeWithoutResult(status -> {
            // 取得活动排他锁
            SeckillCampaignControl campaign = campaigns.lockExclusive(snapshot.getVoucherId());
            // 取得请求并检查代次
            SeckillRequest request = requireRequest(requests.lockByOrderId(orderId));
            // 请求还没固定代次或仍属于当前代次才暂停；旧代次只留审计，不暂停新活动。
            if (campaign != null && (request.getReservationEpoch() == null
                    || Objects.equals(campaign.getEpoch(), request.getReservationEpoch()))) {
                maintenance.pause(request.getVoucherId(), campaign.getEpoch());
            }
            requireOne(maintenance.audit(orderId, request.getVoucherId(), "CONSISTENCY_CONFLICT", "SYSTEM", conflictReason(failure)));
        });
        log.error("SECKILL_CONSISTENCY orderId={} voucherId={} errorType={}", orderId, snapshot.getVoucherId(), failure.getClass().getSimpleName());
    }

    /**
     * 用请求账本已有的订单、用户、券、跟踪号和受理时间组装消息。
     * 按任务类型选 order-、confirm- 或 release- 前缀，重投和补偿都沿用原订单号。
     *
     * @param request 含原受理时间和预占代次的请求记录
     * @param type    CREATE_ORDER、CONFIRM_RESERVATION 或 RELEASE_RESERVATION
     * @return 与原请求身份一致的版本 1 消息，尚未序列化或持久化
     * @throws SeckillConsistencyException 请求缺少原受理时间或预占代次
     */
    private SeckillOrderMessage message(SeckillRequest request, String type) {
        if (request.getAcceptedAt() == null || request.getReservationEpoch() == null) {
            throw new SeckillConsistencyException("账本缺少原受理时间或预占代次");
        }
        // 如果任务类型是创建订单则使用 order- 前缀，否则使用 confirm- 或 release- 前缀
        String prefix = CREATE_ORDER_EVENT_TYPE.equals(type) ? "order-"
                : CONFIRM_RESERVATION_EVENT_TYPE.equals(type) ? "confirm-" : "release-";
        return new SeckillOrderMessage(1, prefix + request.getOrderId(), request.getOrderId(), request.getUserId(),
                request.getVoucherId(), request.getTraceId(), request.getAcceptedAt());
    }

    /**
     * 把请求账本组装为一条可保存到 Outbox 的建单、确认或释放事件。
     * 本方法只构造事件及 JSON 正文，实际插入由外层业务事务执行。
     *
     * @param request 含原订单编号、用户、券、跟踪号、受理时间及预占代次的请求
     * @param type    CREATE_ORDER、CONFIRM_RESERVATION 或 RELEASE_RESERVATION
     * @return 含固定事件编号、类型、版本及 JSON 正文的新 Outbox 对象，尚未入库
     * @throws SeckillConsistencyException 请求缺少原受理时间或预占代次
     * @throws IllegalStateException       消息转为 JSON 失败，外层事务需回滚
     */
    private MqOutbox event(SeckillRequest request, String type) {
        SeckillOrderMessage message = message(request, type);
        MqOutbox event = new MqOutbox();
        event.setEventId(message.eventId());
        event.setOrderId(message.orderId());
        event.setEventType(type);
        event.setSchemaVersion(1);
        try {
            event.setPayload(json.writeValueAsString(message));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("维护任务序列化失败", ex);
        }
        return event;
    }

    /**
     * 把 Outbox 的 JSON 正文还原为消息，并核对正文与表中事件编号、订单编号及版本是否相同。
     * 防止正文被改为另一订单后仍借用原任务的处理资格。
     *
     * @param event 数据库中保存的原事件及 JSON 正文
     * @return 解析成功且编号和版本与表记录一致的消息；尚未与请求账本核对
     * @throws SeckillConsistencyException 正文非法、消息为空，或事件编号、订单编号、版本不匹配
     */
    private SeckillOrderMessage decode(MqOutbox event) {
        try {
            SeckillOrderMessage message = json.readValue(event.getPayload(), SeckillOrderMessage.class);
            if (message == null || !Objects.equals(message.eventId(), event.getEventId()) || !Objects.equals(message.orderId(), event.getOrderId())
                    || message.schemaVersion() != event.getSchemaVersion()) {
                throw new SeckillConsistencyException("任务外层标识与消息不一致");
            }
            return message;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new SeckillConsistencyException("任务消息无法解析");
        }
    }

    /**
     * 检查任务正文是否与当前请求的事件编号、订单、用户、券、跟踪号和受理时间逐项相同。
     * 字段不同就停止确认或释放，避免用一个任务操作另一请求的预占。
     *
     * @param request 当前事务中已锁定的请求记录
     * @param actual  从待执行任务正文解析出的消息
     * @param type    当前任务类型，用于生成应有的事件编号前缀
     * @throws SeckillConsistencyException 任一消息字段不同，或请求缺少原受理时间及预占代次
     */
    private void verifyMessage(SeckillRequest request, SeckillOrderMessage actual, String type) {
        if (!message(request, type).equals(actual)) {
            throw new SeckillConsistencyException("任务与请求账本身份不一致");
        }
    }

    /**
     * 检查活动控制行的库存版本号是否等于该请求预占时记录的版本号。
     * 活动不存在或版本不一致时停止本次操作，防止旧任务修改新版本库存；本方法不检查活动是否 READY。
     *
     * @param campaign 当前事务锁定的活动控制行，允许为 null
     * @param request  当前事务锁定的请求记录，其 reservationEpoch 为预占时的库存版本号
     * @throws SeckillConsistencyException 活动不存在、请求未记录代次或两者代次不相同
     */
    private void requireEpoch(SeckillCampaignControl campaign, SeckillRequest request) {
        if (campaign == null || request.getReservationEpoch() == null
                || !Objects.equals(campaign.getEpoch(), request.getReservationEpoch())) {
            throw new SeckillConsistencyException("活动代次与请求预占代次不一致");
        }
    }

    /**
     * 检查数据库原订单是否属于当前请求的同一订单编号、用户和优惠券。
     * 用于判断是否确实已经为该请求建单，不检查订单支付状态。
     *
     * @param order   按原订单编号查到的数据库订单，允许为 null
     * @param request 待比对的请求记录
     * @return 订单存在且订单号、用户号、券号全部相同为 true；订单不存在或任一项不同为 false
     */
    private boolean matching(VoucherOrder order, SeckillRequest request) {
        return order != null && Objects.equals(order.getId(), request.getOrderId())
                && Objects.equals(order.getUserId(), request.getUserId()) && Objects.equals(order.getVoucherId(), request.getVoucherId());
    }

    /**
     * 确保本次关闭、重投或补偿操作有对应请求记录，没有记录就立即停止。
     *
     * @param request 查询或加锁得到的请求，允许为 null
     * @return 传入的非 null 请求对象，不修改其状态
     * @throws IllegalStateException 请求为 null
     */
    private SeckillRequest requireRequest(SeckillRequest request) {
        if (request == null) {
            throw new IllegalStateException("请求账本不存在");
        }
        return request;
    }

    /**
     * 检查重投、关闭和隔离处置所用的订单编号、操作者及原因格式。
     * 这里只校验参数值和长度；登录身份及独立运维令牌由 Controller 校验。
     *
     * @param orderId  正数订单编号；隔离处置没有订单，内部传入固定正数以复用其余校验
     * @param operator 可信登录操作者编号或 SYSTEM，非空白且长度 1 到 128 字符
     * @param reason   操作依据，非空白且长度 1 到 256 字符
     * @throws IllegalArgumentException 任一参数为空或不符合约定
     */
    private void validateOperation(Long orderId, String operator, String reason) {
        if (orderId == null || orderId < 1 || operator == null || operator.isBlank() || operator.length() > 128
                || reason == null || reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("订单编号、操作者或操作原因非法");
        }
    }

    /**
     * 提取代码中定义的一致性冲突说明，供暂停活动时保存到审计记录。
     * 可识别直接冲突及其受理包装异常；其他异常只记录类型，避免保存外部异常正文中的敏感信息。
     *
     * @param failure 直接一致性异常，或其 cause 可能为一致性异常的受理异常
     * @return 最多 256 字符的冲突说明；无法提取时返回外层异常的简单类名
     */
    private String conflictReason(RuntimeException failure) {
        Throwable actual = failure instanceof SeckillConsistencyException ? failure : failure.getCause();
        String reason = actual instanceof SeckillConsistencyException && actual.getMessage() != null
                ? actual.getMessage() : failure.getClass().getSimpleName();
        return reason.substring(0, Math.min(256, reason.length()));
    }

    /**
     * 在开始维护操作前检查配置，拒绝被外层数据库事务包裹或在已中断线程中运行。
     * 维护流程自行开启并提交短事务，调用方不能用自己的回滚来撤销已经执行的 Redis 操作。
     *
     * @throws IllegalArgumentException 维护批次、间隔、超时或运维令牌等配置不合法
     * @throws IllegalStateException    当前线程已有事务或已经中断
     */
    private void requireStandalone() {
        // 验证维护配置
        properties.validate();
        // 验证订单配置
        orderProperties.validate();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("秒杀维护不能从已有事务内调用");
        }
        requireUninterrupted();
    }

    /**
     * 检查线程是否已被要求停止；已中断时抛异常，让外层停止本次操作并保留待办。
     * 不清除中断标记，也不在这里重试任务。
     *
     * @throws IllegalStateException 当前线程的中断标记为 true
     */
    private void requireUninterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("维护线程已中断，任务保留重试");
        }
    }

    /**
     * 创建用于领取、确认、关闭、恢复和审计的独立数据库短事务。
     * 使用 READ_COMMITTED 读取已提交数据，REQUIRES_NEW 独立提交；超时取秒杀事务配置。
     * 模板本身不加锁，锁由事务回调中的查询取得，并在事务提交或回滚后释放。
     *
     * @return 尚未开始执行的事务模板，使用它的 execute 方法才会开始事务
     */
    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(orderProperties.getTransactionTimeoutSeconds());
        return template;
    }

    /**
     * 根据已经领取任务的次数计算下一次执行需等待多久，避免失败任务持续占用数据库和 Redis。
     * 首次等待 1 秒，此后翻倍，最长等待 5 分钟；达到告警次数仍不删除任务。
     *
     * @param attempts 已领取次数，首次为 1；小于 1 时也按首次计算
     * @return 等待毫秒数，范围为 1000 到 300000
     */
    private long retryDelay(int attempts) {
        return Math.min(300000L, 1000L << Math.min(18, Math.max(0, attempts - 1)));
    }

    /**
     * 确认本次业务写入恰好影响一行，否则让外层事务失败，避免把未写入或错误写入当作成功。
     * 用于必须成功的状态修改、任务登记和审计；有效租约仍被占用时，重投更新也可能为 0。
     *
     * @param affected 本次 SQL 实际影响的行数
     * @throws IllegalStateException 影响行数不是 1
     */
    private void requireOne(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("维护状态更新失败或存在有效租约");
        }
    }
}
