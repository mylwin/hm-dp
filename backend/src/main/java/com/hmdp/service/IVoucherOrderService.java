package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 旧 Redis Stream 链路的秒杀受理和建单接口，仅保留历史参考。
 * 对应的旧 Controller 和实现类已停止注册，新业务不再注入或调用此接口。
 *
 * @author mylwin
 * @since 2026-05-23
 * @deprecated 秒杀受理与结果查询使用 {@link ISeckillRequestService}，消息消费建单使用 {@link ISeckillOrderService}
 */
@Deprecated
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * 从登录上下文取得用户，通过旧 Lua 暂扣库存并把订单写入 Redis Stream。
     * 仅供旧实现参考；返回订单号时数据库订单可能尚未创建。
     *
     * @param voucherId 旧链路要抢购的秒杀券编号
     * @return 旧 Lua 接受时返回成功及订单号；库存不足或已有购买资格时返回失败
     * @deprecated 使用 {@link ISeckillRequestService#accept(Long, Long, String, String)}，重试沿用原 requestId
     */
    @Deprecated
    Result seckillVoucher(Long voucherId);

    /**
     * 按旧 Stream 消息给出的订单、用户和券编号查重、条件扣减数据库库存并调用订单保存。
     * 仅供旧实现通过事务代理调用；当前 RocketMQ 消费者不使用此入口。
     *
     * @param voucherOrder 旧消息中固定订单号、用户号和券号组成的订单资料
     * @return true 表示旧流程通过查重、扣库并正常结束保存调用；false 表示已有同券订单或库存不足
     * @deprecated 使用 {@link ISeckillOrderService#createFromMessage(com.hmdp.infrastructure.mq.SeckillOrderMessage)} 按请求账本幂等建单
     */
    @Deprecated
    boolean createVoucherOrder(VoucherOrder voucherOrder);
}
