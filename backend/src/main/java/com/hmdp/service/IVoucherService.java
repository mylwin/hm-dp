package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 优惠券服务接口
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface IVoucherService extends IService<Voucher> {

    /**
     * 为用户直接创建上架普通券的未支付订单，不预占库存、不发送 MQ。
     * 普通券没有独立库存，同一用户同券已有订单时返回原订单号，包括券后来下架的情况。
     * 在独立短事务中锁定券记录后查重和插单，提交成功后才返回结果。
     *
     * @param userId 当前登录用户的正数编号，由后端鉴权上下文取得
     * @param voucherId 要购买的正数券编号，只接受 type=0 的普通券
     * @return 成功时 data 为字符串订单号；券不存在、类型不符或未上架且没有原订单时返回失败
     * @throws IllegalArgumentException 用户或券编号为空、非正数
     * @throws IllegalStateException 已有外层事务、线程中断或订单插入没有成功写入一行
     */
    Result purchaseVoucher(Long userId, Long voucherId);

    /**
     * 查询店铺可展示的优惠券及秒杀库存信息。
     *
     * @param shopId 店铺编号
     * @return 成功结果及券列表，没有优惠券时列表为空
     */
    Result queryVoucherOfShop(Long shopId);

    /**
     * 创建秒杀券及数据库库存，并在可靠组件开启时准备新 Redis 活动。
     * 先独立提交券、库存和 PAUSED/PENDING 控制行，再初始化 Redis，最后开放 READY/PUBLISHED。
     * 发布阶段未确认时券已存在，调用方应沿用返回的原券编号重试初始化；组件关闭时沿用旧库存键。
     *
     * @param voucher 新券资料，编号必须为空，库存非负，开始时间按秒截断后早于结束时间，时间按 UTC 解释
     * @throws IllegalArgumentException 新券资料、库存或时间不合法
     * @throws com.hmdp.exception.SeckillCampaignPublicationPendingException 券已提交但发布结果未确认，异常携带可重试的原券编号
     * @throws IllegalStateException 已有外层事务、线程已中断、组件缺失或券及库存保存失败
     */
    void addSeckillVoucher(Voucher voucher);

    /**
     * 沿用已有券编号，完成首次 Redis 初始化并开放购买，或核对已经发布的活动。
     * PAUSED/PENDING 才允许首次创建键，通过后改为 READY/PUBLISHED；已发布活动只核对已有键，不重置库存。
     * 活动排他锁覆盖 Redis 调用和数据库提交；历史、BLOCKED 和已发布后暂停的活动均不在此入口重新开放。
     *
     * @param voucherId 已创建的正数券编号；重试不能重新创建一张券
     * @throws IllegalArgumentException 券编号、数据库库存或活动时间不合法
     * @throws IllegalStateException 已有外层事务、线程中断、开关关闭、组件缺失、状态不允许或 Redis 数据不符合要求
     */
    void initializeSeckillCampaign(Long voucherId);
}
