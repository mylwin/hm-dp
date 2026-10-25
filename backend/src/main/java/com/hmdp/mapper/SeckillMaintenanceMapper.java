package com.hmdp.mapper;

import com.hmdp.entity.QuarantinedOrderMessage;
import com.hmdp.entity.SeckillRequest;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 维护扫描、租约回执、运维审计与隔离记录，不执行业务状态推导。
 *
 * @author myl
 */
public interface SeckillMaintenanceMapper {
    /**
     * 找出长时间没有更新、仍处于 PREPARING 或 ACCEPTED 的请求，供后台逐条恢复。
     * 仍在等待下一次恢复时间的请求本轮不返回，避免失败数据反复占满批次；本查询不加行锁或领取恢复资格。
     *
     * @param staleSeconds 距离请求最后更新时间至少经过的秒数，由业务层校验为正数
     * @param limit 本轮最多返回条数，由业务层限制为 1 到 100
     * @return 按下次恢复时间和原订单号排序的请求快照；没有到期请求时为空列表
     */
    @Select("""
            SELECT r.* FROM tb_seckill_request r LEFT JOIN tb_seckill_recovery_attempt a
              ON a.resource_type='REQUEST' AND a.resource_id=r.order_id
            WHERE r.status IN ('PREPARING','ACCEPTED')
              AND r.updated_at <= TIMESTAMPADD(SECOND, -#{staleSeconds}, UTC_TIMESTAMP(3))
              AND (a.next_retry_at IS NULL OR a.next_retry_at <= UTC_TIMESTAMP(3))
            ORDER BY COALESCE(a.next_retry_at, r.updated_at), r.order_id LIMIT #{limit}
            """)
    List<SeckillRequest> staleRequests(int staleSeconds, int limit);

    /**
     * 找出券已保存、活动仍为 PAUSED/PENDING 且允许再次检查的活动，供后台继续首次发布。
     * 历史 UNMANAGED 和冲突 BLOCKED 活动不在本查询范围；此查询只列候选，不改变活动状态。
     *
     * @param limit 本轮最多返回的券编号数量，由业务层限制为 1 到 100
     * @return 待继续首次发布的券编号列表，没有候选时为空列表
     */
    @Select("""
            SELECT c.voucher_id FROM tb_seckill_campaign_control c LEFT JOIN tb_seckill_recovery_attempt a
              ON a.resource_type='CAMPAIGN' AND a.resource_id=c.voucher_id
            WHERE c.status='PAUSED' AND c.publication_status='PENDING'
              AND (a.next_retry_at IS NULL OR a.next_retry_at <= UTC_TIMESTAMP(3))
            ORDER BY COALESCE(a.next_retry_at, c.updated_at), c.voucher_id LIMIT #{limit}
            """)
    List<Long> pendingCampaigns(int limit);

    /**
     * 为某个券或请求建立恢复尝试记录，使后续能保存尝试次数、错误类型和下次检查时间。
     * 记录已存在时不覆盖，不能借此清除其他实例已登记的等待时间。
     *
     * @param type CAMPAIGN 表示券发布恢复，REQUEST 表示抢购请求恢复
     * @param id CAMPAIGN 使用券编号，REQUEST 使用原订单编号
     * @return 首次插入记录为 1；对应记录已存在而跳过插入为 0
     */
    @Insert("INSERT IGNORE INTO tb_seckill_recovery_attempt(resource_type, resource_id) VALUES(#{type}, #{id})")
    int ensureAttempt(String type, Long id);

    /**
     * 仅在恢复时间已到时，给指定券或请求登记一次尝试，并推迟下次允许检查的时间。
     * 更新会增加尝试次数并清除上一轮错误；调用方只有更新成功才继续执行业务恢复。
     *
     * @param type CAMPAIGN 或 REQUEST，需与已有恢复记录的类型一致
     * @param id 券编号或原订单编号，需与恢复记录一致
     * @param delaySeconds 从本次领取到下次允许恢复的间隔，单位秒
     * @return 更新到期记录为 1；记录不存在或尚未到期（含已被其他实例推迟）为 0
     */
    @Update("""
            UPDATE tb_seckill_recovery_attempt SET attempts=LEAST(attempts+1,2147483647),
              next_retry_at=TIMESTAMPADD(SECOND,#{delaySeconds},UTC_TIMESTAMP(3)),
              last_error=NULL, updated_at=UTC_TIMESTAMP(3)
            WHERE resource_type=#{type} AND resource_id=#{id} AND next_retry_at<=UTC_TIMESTAMP(3)
            """)
    int claimAttempt(String type, Long id, int delaySeconds);

    /**
     * 在恢复记录上保存本次失败的异常类型，供监控和下次排查使用。
     * 不改变请求状态或下次执行时间；传 null 可以清除错误，不在此方法执行恢复。
     *
     * @param type CAMPAIGN 或 REQUEST
     * @param id 该类型下的券编号或原订单编号
     * @param error 最多 128 字符的异常简单类名，不含正文和凭据；null 表示清除错误
     * @return 数据库报告的更新行数，未找到该资源记录时为 0
     */
    @Update("UPDATE tb_seckill_recovery_attempt SET last_error=#{error} WHERE resource_type=#{type} AND resource_id=#{id}")
    int attemptResult(String type, Long id, String error);

    /**
     * 按原订单编号读取抢购请求，供重投、关闭和冲突处理定位对应活动。
     * 读取结果未加锁，作出业务判断前仍须在事务里重新锁定请求读取最新状态。
     *
     * @param orderId 请求最初绑定的订单编号
     * @return 请求记录；不存在时为 null，不创建新请求
     */
    @Select("SELECT * FROM tb_seckill_request WHERE order_id=#{orderId}")
    SeckillRequest findRequest(Long orderId);

    /**
     * 锁住原事件记录，供维护流程核对正文、租约和更新待投递状态。
     * 应在活动锁和请求锁之后调用；锁冲突立即抛异常，已取得的锁随当前事务结束释放。
     *
     * @param eventId 固定事件编号，如 order-原订单号、confirm-原订单号或 release-原订单号
     * @return 已加排他锁的 Outbox 记录；不存在时为 null
     */
    @Select("SELECT * FROM tb_mq_outbox WHERE event_id=#{eventId} FOR UPDATE NOWAIT")
    com.hmdp.entity.MqOutbox lockEvent(String eventId);

    /**
     * 锁住一条现在可执行的确认或释放任务，供调用方随后发放处理租约。
     * 只选到期 PENDING 或租约已过期的 SENDING，跳过其他线程已锁记录，不领取 CREATE_ORDER。
     * 本查询不改变任务状态，调用方需在同一短事务中登记租约并提交。
     *
     * @return 一条已锁定的本地 Redis 任务；没有可领取任务时为 null
     */
    @Select("""
            SELECT * FROM tb_mq_outbox WHERE event_type IN ('CONFIRM_RESERVATION','RELEASE_RESERVATION')
              AND ((status='PENDING' AND next_retry_at<=UTC_TIMESTAMP(3))
                OR (status='SENDING' AND lease_until<=UTC_TIMESTAMP(3)))
            ORDER BY next_retry_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    com.hmdp.entity.MqOutbox lockNextReservationTask();

    /**
     * 检查任务是否仍为 SENDING、令牌是否属于本实例，并且租约尚未到期。
     * 调用方在任务行锁内检查，通过后才可以执行 Redis，避免旧领取者继续处理。
     *
     * @param eventId 本轮领取的任务编号
     * @param token 本轮随机生成并已提交的处理令牌
     * @return 三项条件全部满足为 1；任务不存在、令牌被替换、已完成或租约到期为 0
     */
    @Select("SELECT COUNT(*) FROM tb_mq_outbox WHERE event_id=#{eventId} AND status='SENDING' "
            + "AND lease_token=#{token} AND lease_until>UTC_TIMESTAMP(3)")
    int validLease(String eventId, String token);

    /**
     * 让同一条 CREATE_ORDER 事件重新等待发送，并清除旧租约，保留原正文和尝试次数。
     * 只能处理 PENDING、SENT 或已过期 SENDING；发送租约仍有效时不能被本次操作抢占。
     * 应在核对请求、预占和原消息之后调用，不在此方法连接 Broker。
     *
     * @param eventId 已核对的原 CREATE_ORDER 事件编号
     * @return 条件更新成功为 1；事件不存在、类型或状态不允许、发送租约仍有效时为 0
     */
    @Update("""
            UPDATE tb_mq_outbox SET status='PENDING', lease_token=NULL, lease_until=NULL,
              next_retry_at=UTC_TIMESTAMP(3), updated_at=UTC_TIMESTAMP(3)
            WHERE event_id=#{eventId} AND event_type='CREATE_ORDER'
              AND (status IN ('PENDING','SENT') OR (status='SENDING' AND lease_until<=UTC_TIMESTAMP(3)))
            """)
    int replayEvent(String eventId);

    /**
     * 把指定未终态请求改成 CLOSED，并保存外层已确定的原因、预占代次及任务时间。
     * 只接受数据库当前仍为 PREPARING 或 ACCEPTED 的记录；释放任务和审计须由调用方在同一事务登记。
     *
     * @param request 已锁定的请求，须带原订单编号、关闭原因、预占代次和非 null 任务时间
     * @return 改为 CLOSED 成功为 1；记录不存在或已被推进为其他终态时为 0
     */
    @Update("""
            UPDATE tb_seckill_request SET status='CLOSED', failure_reason=#{failureReason},
              reservation_epoch=#{reservationEpoch}, accepted_at=#{acceptedAt}, updated_at=UTC_TIMESTAMP(3)
            WHERE order_id=#{orderId} AND status IN ('PREPARING','ACCEPTED')
            """)
    int closeRequest(SeckillRequest request);

    /**
     * 将指定券的当前库存版本标记为 PAUSED/BLOCKED，阻止新受理和首次发布扫描重新开放。
     * 只更新编号和代次同时匹配的活动；调用方须已取得活动排他锁，并在同事务写冲突审计。
     *
     * @param voucherId 要暂停的券编号
     * @param epoch 已锁定并确认发生冲突的活动库存版本号，旧版本不能暂停新版本
     * @return 数据库报告的更新行数，未匹配到该券及代次时为 0
     */
    @Update("UPDATE tb_seckill_campaign_control SET status='PAUSED', "
            + "publication_status='BLOCKED',updated_at=UTC_TIMESTAMP(3) "
            + "WHERE voucher_id=#{voucherId} AND epoch=#{epoch}")
    int pause(Long voucherId, Long epoch);

    /**
     * 保存谁因为什么原因重投、关闭请求、处置隔离消息或暂停活动，便于后续核对。
     * 调用方与对应状态修改放在同一事务，审计写失败时不能把操作当作已完成。
     *
     * @param orderId 对应原订单编号；活动发布或纯隔离处置没有订单时为 null
     * @param voucherId 对应券编号；无法关联活动的隔离处置可为 null
     * @param action REPLAY、CLOSE、DISMISS_QUARANTINE 或 CONSISTENCY_CONFLICT
     * @param operator 当前登录操作者编号或 SYSTEM，最多 128 字符
     * @param reason 操作依据或代码定义的冲突说明，最多 256 字符，不含凭据
     * @return 插入的审计行数，正常为 1
     */
    @Insert("""
            INSERT INTO tb_seckill_maintenance_audit(order_id,voucher_id,action,operator_id,reason)
            VALUES(#{orderId},#{voucherId},#{action},#{operator},#{reason})
            """)
    int audit(Long orderId, Long voucherId, String action, String operator, String reason);

    /**
     * 把非法消息或死信的摘要、元信息及有界原文保存到隔离表，供人工核对。
     * 同一隔离编号重复到达时保留旧记录和已有处置结果；事务提交后调用方才能向 Broker 确认。
     *
     * @param message 隔离编号、来源、消息编号、摘要及最多 64 KiB 原文；超大原文应为 null
     * @return 数据库报告的影响行数；重复记录保持原样，不应仅凭该值判断是否首次隔离
     */
    @Insert("""
            INSERT INTO tb_seckill_message_quarantine
              (quarantine_id,source,message_id,topic,body,body_hash,error_type,reconsume_times)
            VALUES(#{quarantineId},#{source},#{messageId},#{topic},#{body},#{bodyHash},#{errorType},#{reconsumeTimes})
            ON DUPLICATE KEY UPDATE quarantine_id=quarantine_id
            """)
    int quarantine(QuarantinedOrderMessage message);

    /**
     * 读取并锁住一条隔离记录，供重投前核对原文或记录无需重投的处置依据。
     * 锁冲突立即失败，锁保持到当前事务结束；读出的正文不直接返回 HTTP。
     *
     * @param id 隔离记录的 64 位十六进制编号
     * @return 已锁定的隔离记录，含内部核对所需正文；记录不存在时为 null
     */
    @Select("SELECT * FROM tb_seckill_message_quarantine WHERE quarantine_id=#{id} FOR UPDATE NOWAIT")
    QuarantinedOrderMessage lockQuarantine(String id);

    /**
     * 把尚未处置的隔离记录标为已处置，并保存重投或无需重投的说明。
     * 不删除原文，不改变请求、订单或库存；调用方应同时登记审计。
     *
     * @param id 要处置的隔离编号
     * @param reason 最多 256 字符的处置说明
     * @return 首次更新未处置记录为 1；记录不存在或已处置为 0
     */
    @Update("UPDATE tb_seckill_message_quarantine SET resolved=1,resolution=#{reason} WHERE quarantine_id=#{id} AND resolved=0")
    int resolveQuarantine(String id, String reason);

    /**
     * 按时间列出尚未处置的隔离消息，供运维选择要核对的记录。
     * 只查隔离编号、来源、物理消息编号、摘要、错误和时间，不读取正文给接口展示。
     *
     * @param limit 最多返回条数，由业务层限制为 1 到 100
     * @return 最早未处置记录的摘要列表；没有记录时为空列表
     */
    @Select("""
            SELECT quarantine_id,source,message_id,topic,body_hash,error_type,reconsume_times,created_at
            FROM tb_seckill_message_quarantine WHERE resolved=0 ORDER BY created_at,quarantine_id LIMIT #{limit}
            """)
    List<Map<String, Object>> quarantineSummary(int limit);

    /**
     * 统计数据库中未完成任务、未终态请求、待发布和被暂停活动，供排查积压并触发日志告警。
     * 同时返回过期租约、最老待办年龄、最大尝试次数、隔离待办和仍关联活跃资源的恢复错误。
     * 本查询不读取消息正文，也不代表 Broker 的精确积压数量。
     *
     * @return 指标名到数字的映射；年龄单位为秒，没有相关记录时对应计数或最大值为 0
     */
    @Select("""
            SELECT
              (SELECT COUNT(*) FROM tb_mq_outbox WHERE status<>'SENT') AS pending_tasks,
              (SELECT COUNT(*) FROM tb_mq_outbox WHERE status<>'SENT' AND event_type='CREATE_ORDER') AS pending_create_events,
              (SELECT COUNT(*) FROM tb_mq_outbox WHERE status<>'SENT' AND event_type='CONFIRM_RESERVATION') AS pending_confirmations,
              (SELECT COUNT(*) FROM tb_mq_outbox WHERE status<>'SENT' AND event_type='RELEASE_RESERVATION') AS pending_releases,
              (SELECT COUNT(*) FROM tb_mq_outbox WHERE status='SENDING' AND lease_until<=UTC_TIMESTAMP(3)) AS expired_leases,
              (SELECT COALESCE(MAX(attempts),0) FROM tb_mq_outbox WHERE status<>'SENT') AS max_attempts,
              (SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(3))),0) FROM tb_mq_outbox WHERE status<>'SENT') AS oldest_task_seconds,
              (SELECT COUNT(*) FROM tb_seckill_request WHERE status IN ('PREPARING','ACCEPTED')) AS unfinished_requests,
              (SELECT COUNT(*) FROM tb_seckill_request WHERE status='PREPARING') AS preparing_requests,
              (SELECT COUNT(*) FROM tb_seckill_request WHERE status='ACCEPTED') AS accepted_requests,
              (SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(3))),0) FROM tb_seckill_request WHERE status IN ('PREPARING','ACCEPTED')) AS oldest_request_seconds,
              (SELECT COUNT(*) FROM tb_seckill_message_quarantine WHERE resolved=0) AS quarantine_pending,
              (SELECT COUNT(*) FROM tb_seckill_campaign_control WHERE publication_status='PENDING') AS pending_campaigns,
              (SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,updated_at,UTC_TIMESTAMP(3))),0) FROM tb_seckill_campaign_control WHERE publication_status='PENDING') AS oldest_campaign_seconds,
              (SELECT COUNT(*) FROM tb_seckill_campaign_control WHERE publication_status='BLOCKED') AS blocked_campaigns,
              (SELECT COUNT(*) FROM tb_seckill_recovery_attempt a WHERE last_error IS NOT NULL AND
                ((resource_type='REQUEST' AND EXISTS(SELECT 1 FROM tb_seckill_request r WHERE r.order_id=a.resource_id AND r.status IN ('PREPARING','ACCEPTED')))
                  OR (resource_type='CAMPAIGN' AND EXISTS(SELECT 1 FROM tb_seckill_campaign_control c WHERE c.voucher_id=a.resource_id AND c.publication_status IN ('PENDING','BLOCKED'))))) AS recovery_errors
            """)
    Map<String, Object> health();
}
