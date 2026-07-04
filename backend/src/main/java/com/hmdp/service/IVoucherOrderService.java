package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 优惠券订单服务接口
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * 秒杀优惠券
     *
     * @param voucherId 优惠券ID
     * @return 结果
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建优惠券订单
     *
     * @param voucherOrder 优惠券订单
     * @return true表示创建成功，false表示创建失败
     */
    boolean createVoucherOrder(VoucherOrder voucherOrder);
}
