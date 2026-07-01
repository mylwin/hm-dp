package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static com.hmdp.utils.RedisConstants.LOCK_KEY;

/**
 * 优惠券订单服务实现类
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 秒杀优惠券订单
     *
     * @param voucherId 优惠券ID
     * @return 结果
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.根据ID查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        // 2.判断秒杀活动是否开始
        LocalDateTime now = LocalDateTime.now();
        if (seckillVoucher.getBeginTime().isAfter(now)) {
            return Result.fail("秒杀活动未开始！");
        }

        // 3.判断秒杀活动是否结束
        if (seckillVoucher.getEndTime().isBefore(now)) {
            return Result.fail("秒杀活动已结束！");
        }

        // 4.判断当前优惠券库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }

        // 5.添加redis分布式锁，确保一人一单
        Long userID = UserHolder.getUser().getId();
        // 5.1尝试获取锁
        // SimpleRedisLock simpleRedisLock = new SimpleRedisLock("seckill_voucher:" + voucherId + ":" + userID, stringRedisTemplate);
        RLock lock = redissonClient.getLock(LOCK_KEY + "seckill_voucher:" + voucherId + ":" + userID);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 5.2获取锁失败
            return Result.fail("请勿重复下单！");
        }
        try {
            // @Transactional 自调用问题（本质：目标对象内部的方法调用本对象的另一个带事务注解的方法）在运行时不会生效。
            // 因此需要使用AopContext.currentProxy()获取当前代理对象，从而调用带事务注解的方法，从而开启真正的事务。
            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
            return currentProxy.createVoucherOrder(voucherId);
        } finally {
            // 5.3释放锁
            lock.unlock();
        }
    }

    /**
     * 创建优惠券订单
     *
     * @param voucherId 优惠券ID
     * @return 结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Result createVoucherOrder(Long voucherId) {
        // 1.当前用户是否已经购买过该优惠券
        Long userID = UserHolder.getUser().getId();
        Long count = query()
                .eq("user_id", userID)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            return Result.fail("用户已购买过该优惠券！");
        }

        // 2.扣减库存（乐观锁确保不会超卖）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }

        // 3.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.getNextId("voucher_order"));
        voucherOrder.setUserId(userID);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 4.返回订单ID
        return Result.ok(voucherOrder.getId());
    }
}
