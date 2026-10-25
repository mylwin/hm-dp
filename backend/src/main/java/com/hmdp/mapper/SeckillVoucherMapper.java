package com.hmdp.mapper;

import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀优惠券 Mapper
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    /**
     * 库存大于零时扣减一份，与插单放在同一事务中。
     * @param voucherId 券编号
     * @return 1 表示扣减成功，0 表示券不存在或库存不足
     */
    @Update("UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = #{voucherId} AND stock > 0")
    @Options(timeout = 5)
    int deductOne(Long voucherId);

}
