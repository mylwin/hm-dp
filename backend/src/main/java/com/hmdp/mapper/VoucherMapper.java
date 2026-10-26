package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Voucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

import java.util.List;

/**
 * 优惠券 Mapper
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    /**
     * 锁定要购买的券记录，串行执行普通券的状态检查、用户查重和插单。
     * 锁由调用方事务持有至提交或回滚，商家修改该券时也需等待当前事务结束。
     *
     * @param voucherId 要购买的正数券编号
     * @return 最新券资料；不存在时为 null
     */
    @Select("SELECT * FROM tb_voucher WHERE id = #{voucherId} FOR UPDATE")
    @Options(timeout = 5)
    Voucher lockForPurchase(Long voucherId);

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
