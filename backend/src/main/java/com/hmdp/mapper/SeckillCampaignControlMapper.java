package com.hmdp.mapper;

import com.hmdp.entity.SeckillCampaignControl;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

/**
 * 活动维护控制，不锁真实库存行。
 *
 * @author myl
 */
public interface SeckillCampaignControlMapper {
    /**
     * 在当前事务中取得活动共享锁，让受理、建单或补偿与发布、暂停操作互相协调。
     * 多个业务请求可以共享此锁；活动排他锁被占用时立即失败，已有锁保持至当前事务提交或回滚。
     *
     * @param voucherId 要读取并保护的券编号
     * @return 活动的当前库存版本号和开放状态；没有控制行时为 null
     */
    @Select("SELECT voucher_id, epoch, status FROM tb_seckill_campaign_control WHERE voucher_id = #{voucherId} FOR SHARE NOWAIT")
    @Options(timeout = 5)
    SeckillCampaignControl lockShared(Long voucherId);

    /**
     * 给刚保存的新券登记首个库存版本 epoch=1，并设为 PAUSED/PENDING 等待 Redis 准备。
     * 须与券和数据库库存一起提交，不在此 SQL 开放活动或写 Redis。
     *
     * @param voucherId 同一事务中新创建的券编号
     * @return 插入行数，正常为 1；编号已存在会由数据库报冲突
     */
    @Insert("INSERT INTO tb_seckill_campaign_control(voucher_id, epoch, status, publication_status) "
            + "VALUES (#{voucherId}, 1, 'PAUSED', 'PENDING')")
    int insertPending(Long voucherId);

    /**
     * 在当前事务中独占指定活动控制行，阻止受理、建单及补偿跨过活动发布或暂停操作。
     * 锁冲突立即失败；成功后读取的发布状态供调用方判断能否首次初始化，锁持有到提交或回滚。
     *
     * @param voucherId 本次准备发布或暂停的券编号
     * @return 含库存版本、开放状态和发布状态的活动控制行；不存在时为 null
     */
    @Select("SELECT voucher_id, epoch, status, publication_status FROM tb_seckill_campaign_control "
            + "WHERE voucher_id = #{voucherId} FOR UPDATE NOWAIT")
    @Options(timeout = 5)
    SeckillCampaignControl lockExclusive(Long voucherId);

    /**
     * 在 Redis 库存及元数据已确认准备好之后，将首次发布中的活动开放为 READY/PUBLISHED。
     * 只能推进同编号、同代次的 PAUSED/PENDING；调用方需持活动排他锁，提交后才对其他请求生效。
     *
     * @param voucherId 本次首次发布的原券编号
     * @param epoch 锁内读取并已传给 Redis 初始化的库存版本号
     * @return 条件更新成功为 1；记录缺失、状态或代次不匹配为 0
     */
    @Update("UPDATE tb_seckill_campaign_control SET status = 'READY', publication_status = 'PUBLISHED', "
            + "updated_at = UTC_TIMESTAMP(3) WHERE voucher_id = #{voucherId} AND epoch = #{epoch} "
            + "AND status = 'PAUSED' AND publication_status = 'PENDING'")
    int markReady(Long voucherId, Long epoch);
}
