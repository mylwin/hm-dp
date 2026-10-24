package com.hmdp.mapper;

import com.hmdp.entity.SeckillRequest;
import org.apache.ibatis.annotations.*;

/**
 * 请求账本读写；状态更新必须由调用方置于受理事务内。
 *
 * @author myl
 */
public interface SeckillRequestMapper {
    /**
     * 登记 PREPARING，重复请求不覆盖原编号、参数或状态。
     *
     * @param request 首次请求参数
     * @return 数据库影响行数，重复记录可能为 0
     */
    @Insert("""
            INSERT INTO tb_seckill_request
              (order_id, user_id, voucher_id, request_id, payload_hash, trace_id, status)
            VALUES (#{orderId}, #{userId}, #{voucherId}, #{requestId}, #{payloadHash}, #{traceId}, 'PREPARING')
            ON DUPLICATE KEY UPDATE order_id = order_id
            """)
    @Options(timeout = 5)
    int insertPreparing(SeckillRequest request);

    /**
     * 按用户隔离重试标识，读取它绑定的原请求。
     *
     * @param userId    请求所属用户
     * @param requestId 客户端重试标识
     * @return 请求账本，不存在返回 null
     */
    @Select("SELECT * FROM tb_seckill_request WHERE user_id = #{userId} AND request_id = #{requestId}")
    @Options(timeout = 5)
    SeckillRequest findByRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

    /**
     * 锁定请求最新状态；已有锁冲突立即失败，调用方保留原请求供重试。
     *
     * @param orderId 固定订单编号
     * @return 请求账本，不存在返回 null
     */
    @Select("SELECT * FROM tb_seckill_request WHERE order_id = #{orderId} FOR UPDATE NOWAIT")
    @Options(timeout = 5)
    SeckillRequest lockByOrderId(Long orderId);

    /**
     * 把首次预占成功记录转为已受理，必须同时插入 Outbox。
     *
     * @param request 含预占代次和受理时间的请求
     * @return 更新行数，应为 1
     */
    @Update("""
            UPDATE tb_seckill_request SET status = 'ACCEPTED', reservation_epoch = #{reservationEpoch},
              accepted_at = #{acceptedAt}, updated_at = UTC_TIMESTAMP(3)
            WHERE order_id = #{orderId} AND status = 'PREPARING'
            """)
    @Options(timeout = 5)
    int markAccepted(SeckillRequest request);

    /**
     * 保存已知的拒绝结果；Redis 异常或超时不得调用此方法。
     *
     * @param orderId 请求编号
     * @param reason  稳定业务原因码
     * @return 更新行数，应为 1
     */
    @Update("""
            UPDATE tb_seckill_request SET status = 'REJECTED', failure_reason = #{reason}, updated_at = UTC_TIMESTAMP(3)
            WHERE order_id = #{orderId} AND status = 'PREPARING'
            """)
    @Options(timeout = 5)
    int markRejected(@Param("orderId") Long orderId, @Param("reason") String reason);
}
