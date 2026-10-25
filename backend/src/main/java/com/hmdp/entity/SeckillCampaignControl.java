package com.hmdp.entity;

import lombok.Data;

/**
 * 独立的活动维护锁资源，不在真实库存行上进行共享锁升级。
 *
 * @author myl
 */
@Data
public class SeckillCampaignControl {
    /**
     * 券ID
     */
    private Long voucherId;
    /**
     * 时间轮盘的轮次
     */
    private Long epoch;
    /**
     * 状态
     */
    private String status;
    /**
     * 首次发布状态：UNMANAGED 历史活动、PENDING 待发布、PUBLISHED 已发布、BLOCKED 冲突暂停。
     */
    private String publicationStatus;
}
