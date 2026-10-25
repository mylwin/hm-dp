package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.entity.SeckillCampaignControl;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.exception.SeckillCampaignPublicationPendingException;
import com.hmdp.infrastructure.seckill.SeckillReservationStore;
import com.hmdp.mapper.SeckillCampaignControlMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.time.LocalDateTime;

import static com.hmdp.constants.SeckillConstants.*;

/**
 * 优惠券服务实现类
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Service
@RequiredArgsConstructor
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    private final ISeckillVoucherService seckillVoucherService;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillCampaignControlMapper campaigns;
    // 预占组件随可靠组件开关注册；旧发券路径不强制注入该 Bean。
    private final ObjectProvider<SeckillReservationStore> reservationStores;
    private final PlatformTransactionManager transactionManager;
    private final SeckillOrderProperties properties;

    /**
     * {@inheritDoc}
     */
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

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
    @Override
    public void addSeckillVoucher(Voucher voucher) {
        // 确保方法调用在独立事务中执行，避免已有事务影响新券创建。
        requireStandaloneCall();
        // 校验优惠券资料
        validateVoucher(voucher);
        // 校验秒杀订单配置
        properties.validate();
        // 如果可靠组件未启用，则直接使用旧发券路径
        if (!properties.isReliableComponentsEnabled()) {
            transaction().executeWithoutResult(status -> {
                saveSeckillVoucher(voucher);
                // 将优惠券数量保存到redis中
                stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(),
                        String.valueOf(voucher.getStock()));
            });
            return;
        }
        // 确认可靠组件存在，避免配置不完整时先创建无法发布的新券。
        requireReservationStore();
        // 创建新券及 PAUSED 状态写入活动控制表，任何写入失败均回滚。
        transaction().executeWithoutResult(status -> {
            saveSeckillVoucher(voucher);
            requireOne(campaigns.insertPending(voucher.getId()));
        });
        // 新券及 PAUSED 已提交，Redis 失败也保留原编号，不回滚或重新发券。
        try {
            initializeSeckillCampaign(voucher.getId());
        } catch (RuntimeException ex) {
            throw new SeckillCampaignPublicationPendingException(voucher.getId(), ex);
        }
    }

    /**
     * 沿用已有券编号，完成首次 Redis 初始化并开放购买，或核对已经发布的活动。
     * PAUSED/PENDING 才允许首次创建键，通过后改为 READY/PUBLISHED；已发布活动只核对已有键，不重置库存。
     * 活动排他锁覆盖 Redis 调用和数据库提交；历史、BLOCKED 和已发布后暂停的活动均不在此入口重新开放。
     *
     * @param voucherId 已创建的正数券编号；重试不能重新创建一张券
     * @throws IllegalArgumentException 券编号、数据库库存或活动时间不合法
     * @throws IllegalStateException 已有外层事务、线程中断、开关关闭、组件缺失、状态不允许或 Redis 数据不符合要求
     */
    @Override
    public void initializeSeckillCampaign(Long voucherId) {
        // 确保方法调用在独立事务中执行，避免已有事务影响新券创建。
        requireStandaloneCall();
        if (voucherId == null || voucherId < 1) {
            throw new IllegalArgumentException("券编号必须为正数");
        }
        if (!properties.isReliableComponentsEnabled()) {
            throw new IllegalStateException("可靠组件未启用，不能初始化新链路活动");
        }
        // 校验秒杀订单配置
        properties.validate();
        // 确认可靠组件存在，避免配置不完整时先创建无法发布的新券。
        SeckillReservationStore store = requireReservationStore();
        transaction().executeWithoutResult(status -> {
            // 与受理、建单的共享锁互斥，锁一直覆盖 Redis 准备和 READY 提交。
            SeckillCampaignControl campaign = campaigns.lockExclusive(voucherId);
            if (campaign == null) {
                throw new IllegalStateException("活动控制不存在，不自动接管历史券");
            }
            boolean pending = "PAUSED".equals(campaign.getStatus())
                    && "PENDING".equals(campaign.getPublicationStatus());
            boolean published = "READY".equals(campaign.getStatus())
                    && "PUBLISHED".equals(campaign.getPublicationStatus());
            if (!pending && !published) {
                throw new IllegalStateException("仅支持新券首次发布重试，不接管历史或重新开放暂停活动");
            }
            // 获取秒杀券资料
            SeckillVoucher seckill = seckillVoucherService.getById(voucherId);
            if (seckill == null) {
                throw new IllegalStateException("数据库秒杀库存不存在");
            }
            // 校验库存及秒杀时间
            validateStockAndTime(seckill.getStock(), seckill.getBeginTime(), seckill.getEndTime());
            // 确保线程未中断
            requireUninterrupted();
            store.initializeCampaign(voucherId, campaign.getEpoch(), seckill.getStock(),
                    seckill.getBeginTime(), seckill.getEndTime(), pending);
            requireUninterrupted();
            if (pending) {
                requireOne(campaigns.markReady(voucherId, campaign.getEpoch()));
            }
            // 排他锁由数据库持有至 READY 提交或回滚，不在 Redis 调用之后提前释放。
        });
    }

    /**
     * 在调用方事务中分别保存基本券资料和秒杀库存，成功后把生成的券编号回填到入参。
     * 任一次保存失败就抛异常，交外层回滚两张表；本方法不写 Redis，也不提交事务。
     *
     * @param voucher 已校验的新券资料，包含库存和 UTC 活动时间，保存后 id 为数据库生成编号
     * @throws IllegalStateException 基本券或秒杀库存保存返回失败
     */
    private void saveSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        if (!save(voucher)) {
            throw new IllegalStateException("优惠券保存失败");
        }
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("秒杀库存保存失败");
        }
    }

    /**
     * 在开始创建券或初始化活动前，拒绝已有外层数据库事务和已中断线程。
     * 避免调用方误认为自己的回滚能撤销已经独立提交的新券或已经写入的 Redis 数据。
     *
     * @throws IllegalStateException 当前已有数据库事务或线程中断标记为 true
     */
    private void requireStandaloneCall() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("秒杀发券和活动初始化不能从已有事务内调用");
        }
        requireUninterrupted();
    }

    /**
     * 检查当前线程是否已被要求停止，已中断时阻止继续写数据库 READY。
     * 保留中断标记；若 Redis 已准备好，仍需沿用原券编号再次核对，不在这里自动重试。
     *
     * @throws IllegalStateException 线程中断标记为 true
     */
    private void requireUninterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("活动发布线程已中断，请沿用原券编号重试");
        }
    }

    /**
     * 取得随可靠组件开关注册的 Redis 预占组件，供新券发布和原券初始化使用。
     * 启用新链路但组件不存在时立即失败，避免误写旧库存或创建无法发布的新券。
     *
     * @return 已注册的新链路 Redis 组件
     * @throws IllegalStateException 可靠 Redis 组件没有注册
     */
    private SeckillReservationStore requireReservationStore() {
        // 获取按开关注册的 Redis 组件
        SeckillReservationStore store = reservationStores.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("新链路 Redis 组件未就绪");
        }
        return store;
    }

    /**
     * 检查发券资料确实用于创建新券，并检查库存和活动时间是否有效。
     * 带已有编号的请求应使用初始化重试入口，不能再次保存为新券。
     *
     * @param voucher 新券资料，不允许为 null，id 必须为空
     * @throws IllegalArgumentException 资料为空、已有编号、库存非法或活动时间无效
     */
    private void validateVoucher(Voucher voucher) {
        if (voucher == null || voucher.getId() != null) {
            throw new IllegalArgumentException("新建秒杀券不能指定已有编号，请使用初始化重试入口");
        }
        validateStockAndTime(voucher.getStock(), voucher.getBeginTime(), voucher.getEndTime());
    }

    /**
     * 检查库存非负，并确保活动时间保存到数据库秒精度后仍有有效区间。
     * 允许零库存活动发布；不因开始时间已过去或活动已结束而把资料判为非法。
     *
     * @param stock 库存数量，允许为 0，不允许为空或为负数
     * @param beginTime UTC 开始时间，不允许为空
     * @param endTime UTC 结束时间，不允许为空，去掉小数秒后须晚于开始时间
     * @throws IllegalArgumentException 库存为空或为负数，时间为空或按秒比较时开始不早于结束
     */
    private void validateStockAndTime(Integer stock, LocalDateTime beginTime, LocalDateTime endTime) {
        if (stock == null || stock < 0 || beginTime == null || endTime == null
                || !beginTime.withNano(0).isBefore(endTime.withNano(0))) {
            throw new IllegalArgumentException("库存必须非负，开始时间必须早于结束时间（UTC，精确到秒）");
        }
    }

    /**
     * 创建独立的券创建或活动发布事务，使用读已提交隔离级别和配置的秒级超时。
     * 创建券与开放活动分两次执行；发布阶段取得的活动锁直到对应事务提交或回滚才释放。
     *
     * @return REQUIRES_NEW、READ_COMMITTED 事务模板，尚未开始执行
     */
    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(properties.getTransactionTimeoutSeconds());
        return template;
    }

    /**
     * 检查活动控制行的插入或开放操作确实写入一行，没有写入就让当前事务失败。
     * 避免数据库活动仍未登记或未开放，却向调用方返回发布成功。
     *
     * @param affected 本次控制行 SQL 实际影响行数
     * @throws IllegalStateException 影响行数不是 1
     */
    private void requireOne(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("活动控制写入行数异常");
        }
    }
}
