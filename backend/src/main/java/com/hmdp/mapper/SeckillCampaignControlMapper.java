package com.hmdp.mapper;

import com.hmdp.entity.SeckillCampaignControl;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/**
 * 活动维护控制，不锁真实库存行。
 *
 * @author myl
 */
public interface SeckillCampaignControlMapper {
    /**
     * 获得维护共享锁；与后续重建的排他锁协调，冲突立即返回异常。
     *
     * @param voucherId 券编号
     * @return 当前代次及开放状态，未初始化返回 null
     */
    @Select("SELECT voucher_id, epoch, status FROM tb_seckill_campaign_control WHERE voucher_id = #{voucherId} FOR SHARE NOWAIT")
    @Options(timeout = 5)
    SeckillCampaignControl lockShared(Long voucherId);
}
