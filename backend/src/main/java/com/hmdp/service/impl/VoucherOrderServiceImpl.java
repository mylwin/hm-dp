package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.LOCK_KEY;
import static com.hmdp.utils.RedisConstants.ORDER_MQ;

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
     * 阻塞队列
     */
    // private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    /**
     * 代理对象
     */
    private IVoucherOrderService proxy;

    /**
     * 处理订单的线程
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 秒杀优惠券的lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 初始化，启动一个线程，监听阻塞队列中的订单信息，并创建订单，确保订单的创建是线程安全的
     */
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 秒杀优惠券订单
     *
     * @param voucherId 优惠券ID
     * @return 结果
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.获取用户id 和 订单id
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.getNextId("voucher_order");
        // 2.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        // 2.1 判断结果是否等于0，等于0，有秒杀资格，否则没有秒杀资格
        int r = result.intValue();
        if (r != 0) {
            // 2.2 返回错误信息
            return Result.fail(r == 1 ? "库存不足！" : "请勿重复下单！");
        }

        // 3.1 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3.2 返回订单id
        return Result.ok(orderId);
    }
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.获取用户id
        Long userId = UserHolder.getUser().getId();

        // 2.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 2.1 判断结果是否等于0
        int r = result.intValue();
        if (r != 0) {
            // 2.2 返回错误信息
            return Result.fail(r == 1 ? "库存不足！" : "请勿重复下单！");
        }
        // 2.3 等于0，有秒杀资格
        long orderId = redisIdWorker.getNextId("voucher_order");
        // 2.4 构建订单对象
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 2.5 添加到阻塞队列
        boolean success = orderTasks.add(voucherOrder);
        if (!success) {
            return Result.fail("系统繁忙，请稍后再试！");
        }

        // 3.1 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3.2 返回订单id
        return Result.ok(orderId);
    }*/

    /*@Override
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
    }*/

    /**
     * 处理订单的线程
     */
    public class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.从消息队列中获取订单 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_MQ, ReadOffset.from(">"))
                    );
                    // 2.判断是否获取到订单
                    if (list == null || list.isEmpty()) {
                        // 没有获取到订单，继续循环
                        continue;
                    }
                    
                    // 3.获取订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 4.创建订单
                    boolean success = handleVoucherOrder(voucherOrder);

                    // 5.订单确认ack（仅处理成功时确认，失败则进入pending list等待重试）
                    if (success) {
                        stringRedisTemplate.opsForStream().acknowledge(ORDER_MQ, "g1", record.getId());
                    }
                } catch (Exception e) {
                    log.error("处理订单异常：", e);
                    // 处理异常为确认的订单
                    handlePendingList();
                }
            }
        }

        /**
         * 处理pending list中的订单
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.从pending list中获取订单 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(ORDER_MQ, ReadOffset.from("0"))
                    );
                    // 2.判断是否获取到订单
                    if (list == null || list.isEmpty()) {
                        // pending list 为空，结束循环
                        break;
                    }

                    // 3.获取订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 4.创建订单
                    boolean success = handleVoucherOrder(voucherOrder);

                    // 5.订单确认ack（仅处理成功时确认，失败则进入pending list等待重试）
                    if (success) {
                        stringRedisTemplate.opsForStream().acknowledge(ORDER_MQ, "g1", record.getId());
                    }
                } catch (Exception e) {
                    log.error("处理订单异常：", e);
                    try {
                        Thread.sleep(20);
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    }
                }
            }
        }

        /**
         * 处理订单
         *
         * @param voucherOrder 订单对象
         * @return 处理结果，true表示处理成功，false表示处理失败
         */
        private Boolean handleVoucherOrder(VoucherOrder voucherOrder) {
            // 1.添加redis分布式锁，确保一人一单
            Long userId = voucherOrder.getUserId();
            // 1.1尝试获取锁
            RLock lock = redissonClient.getLock(LOCK_KEY + "seckill_voucher:" + voucherOrder.getVoucherId() + ":" + userId);
            boolean isLock = lock.tryLock();
            if (!isLock) {
                // 1.2获取锁失败
                log.error("请勿重复下单！");
                return false;
            }
            try {
                // @Transactional 自调用问题（本质：目标对象内部的方法调用本对象的另一个带事务注解的方法）在运行时不会生效。
                // 因此需要使用AopContext.currentProxy()获取当前代理对象，从而调用带事务注解的方法，从而开启真正的事务。
                return proxy.createVoucherOrder(voucherOrder);
            } catch (Exception e) {
                log.error("处理订单异常：", e);
                return false;
            } finally {
                // 5.3释放锁
                lock.unlock();
            }
        }
    }

    /**
     * 创建优惠券订单
     *
     * @param voucherOrder 订单对象
     * @return true表示创建成功，false表示创建失败
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createVoucherOrder(VoucherOrder voucherOrder) {
        // 1.当前用户是否已经购买过该优惠券
        Long userId = voucherOrder.getUserId();
        Long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.error("用户已经购买过该优惠券！");
            return false;
        }

        // 2.扣减库存（乐观锁确保不会超卖）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            return false;
        }

        // 3.保存订单
        save(voucherOrder);
        return true;
    }
}
