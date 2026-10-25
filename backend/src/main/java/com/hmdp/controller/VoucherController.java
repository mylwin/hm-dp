package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.exception.SeckillCampaignPublicationPendingException;
import com.hmdp.service.IVoucherService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;


/**
 * 优惠券控制器
 *
 * @author mylwin
 * @since 2026-05-23
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券，可靠组件开启时自动发布活动。
     * @param voucher 新券信息，时间按 UTC 解释，不能指定编号
     * @return 成功时为券编号；已创建但发布未确认时返回失败结果，data 为原券编号字符串，供初始化重试
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        try {
            voucherService.addSeckillVoucher(voucher);
        } catch (SeckillCampaignPublicationPendingException ex) {
            return new Result(false, ex.getMessage(), ex.getVoucherId().toString(), null);
        }
        return Result.ok(voucher.getId());
    }

    /**
     * 沿用已创建的券编号重试首次发布，不重新创建券或重置已发布库存。
     *
     * @param voucherId 原券编号
     * @return 初始化已确认时返回券编号；活动不符合条件或基础设施异常时由全局异常处理返回失败
     */
    @PostMapping("/seckill/{voucherId}/initialize")
    public Result initializeSeckillCampaign(@PathVariable("voucherId") Long voucherId) {
        voucherService.initializeSeckillCampaign(voucherId);
        return Result.ok(voucherId);
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
