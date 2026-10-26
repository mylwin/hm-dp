package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/**
 * 优惠券订单 Mapper
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /**
     * 查询订单事实，供重复消息核对。
     *
     * @param orderId 订单编号
     * @return 订单，不存在返回 null
     */
    @Select("SELECT * FROM tb_voucher_order WHERE id = #{orderId}")
    @Options(timeout = 5)
    VoucherOrder findOrder(Long orderId);

    /**
     * 查询该用户已有的同券订单。
     *
     * @param userId    用户编号
     * @param voucherId 券编号
     * @return 已有订单，不存在返回 null
     */
    @Select("SELECT * FROM tb_voucher_order WHERE user_id = #{userId} AND voucher_id = #{voucherId} LIMIT 1")
    @Options(timeout = 5)
    VoucherOrder findUserVoucherOrder(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    /**
     * 插入稳定订单号，支付方式和未支付状态沿用表默认值。
     *
     * @param order 待创建订单
     * @return 插入行数，应为 1
     */
    @Insert("INSERT INTO tb_voucher_order(id, user_id, voucher_id) VALUES(#{id}, #{userId}, #{voucherId})")
    @Options(timeout = 5)
    int insertSeckillOrder(VoucherOrder order);

    /**
     * 检查一人一单唯一索引，未迁移时禁止启动新消费者。
     *
     * @return 符合要求的完整唯一索引数量
     */
    @Select("""
            SELECT COUNT(*) FROM (
              SELECT index_name FROM information_schema.statistics
              WHERE table_schema = DATABASE() AND table_name = 'tb_voucher_order' AND non_unique = 0
              GROUP BY index_name
              HAVING COUNT(*) = 2 AND SUM(column_name = 'user_id') = 1
                AND SUM(column_name = 'voucher_id') = 1 AND SUM(sub_part IS NOT NULL) = 0
            ) indexes_found
            """)
    @Options(timeout = 5)
    int countUserVoucherUniqueIndexes();

}
