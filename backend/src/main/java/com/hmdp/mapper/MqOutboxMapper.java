package com.hmdp.mapper;

import com.hmdp.entity.MqOutbox;
import org.apache.ibatis.annotations.*;

/**
 * Outbox 的领取与回执均使用数据库时间和租约令牌。
 *
 * @author myl
 */
public interface MqOutboxMapper {
    /**
     * 在受理事务中登记原始消息，不静默吞掉重复或写入失败。
     *
     * @param event 稳定事件及序列化消息体
     * @return 插入行数，应为 1
     */
    @Insert("""
            INSERT INTO tb_mq_outbox (event_id, order_id, event_type, schema_version, payload)
            VALUES (#{eventId}, #{orderId}, #{eventType}, #{schemaVersion}, #{payload})
            """)
    @Options(timeout = 5)
    int insertEvent(MqOutbox event);

    /**
     * 在短事务内锁一条到期任务，跳过其他实例正在领取的行。
     *
     * @return 待领任务，没有到期任务返回 null
     */
    @Select("""
            SELECT * FROM tb_mq_outbox WHERE event_type = 'CREATE_ORDER' AND
              ((status = 'PENDING' AND next_retry_at <= UTC_TIMESTAMP(3))
                OR (status = 'SENDING' AND lease_until <= UTC_TIMESTAMP(3)))
            ORDER BY next_retry_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    @Options(timeout = 5)
    MqOutbox lockNextDue();

    /**
     * 给已锁任务发放新租约，并计入一次尝试。
     *
     * @param eventId 已锁定事件编号
     * @param token   本轮唯一令牌
     * @param leaseMs 租约毫秒数
     * @return 更新行数，应为 1
     */
    @Update("""
            UPDATE tb_mq_outbox SET status = 'SENDING', lease_token = #{token},
              lease_until = TIMESTAMPADD(MICROSECOND, #{leaseMs} * 1000, UTC_TIMESTAMP(3)),
              attempts = LEAST(attempts + 1, 2147483647), updated_at = UTC_TIMESTAMP(3)
            WHERE event_id = #{eventId}
            """)
    @Options(timeout = 5)
    int claim(@Param("eventId") String eventId, @Param("token") String token, @Param("leaseMs") long leaseMs);

    /**
     * 仅当前有效租约可确认发送；旧进程不能覆盖接管者的状态。
     *
     * @param eventId   事件编号
     * @param token     当前租约令牌
     * @param messageId Broker 回执编号
     * @return 1 表示更新成功，0 表示已失去租约
     */
    @Update("""
            UPDATE tb_mq_outbox SET status = 'SENT', broker_message_id = #{messageId}, last_error = NULL,
              lease_token = NULL, lease_until = NULL, updated_at = UTC_TIMESTAMP(3)
            WHERE event_id = #{eventId} AND status = 'SENDING' AND lease_token = #{token}
              AND lease_until > UTC_TIMESTAMP(3)
            """)
    @Options(timeout = 5)
    int markSent(@Param("eventId") String eventId, @Param("token") String token, @Param("messageId") String messageId);

    /**
     * 在仍持有租约时登记退避重试，不删除任务或释放预占。
     *
     * @param eventId   事件编号
     * @param token     当前租约令牌
     * @param delayMs   下次重试延迟毫秒数
     * @param errorType 异常类型，不包含异常原文或消息体
     * @return 1 表示更新成功，0 表示已失去租约
     */
    @Update("""
            UPDATE tb_mq_outbox SET status = 'PENDING', lease_token = NULL, lease_until = NULL,
              next_retry_at = TIMESTAMPADD(MICROSECOND, #{delayMs} * 1000, UTC_TIMESTAMP(3)),
              last_error = #{errorType}, updated_at = UTC_TIMESTAMP(3)
            WHERE event_id = #{eventId} AND status = 'SENDING' AND lease_token = #{token}
              AND lease_until > UTC_TIMESTAMP(3)
            """)
    @Options(timeout = 5)
    int retryLater(@Param("eventId") String eventId, @Param("token") String token,
                   @Param("delayMs") long delayMs, @Param("errorType") String errorType);
}
