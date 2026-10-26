package com.hmdp.exception;

import lombok.Getter;

/**
 * 新券已提交，首次发布尚未确认；调用方须沿用券编号重试初始化。
 *
 * @author myl
 */
@Getter
public class SeckillCampaignPublicationPendingException extends RuntimeException {
    private final Long voucherId;

    /**
     * 保留已创建券编号，避免发布异常后重新发券。
     *
     * @param voucherId 已提交的新券编号
     * @param cause     发布或提交结果未知的原始异常
     */
    public SeckillCampaignPublicationPendingException(Long voucherId, RuntimeException cause) {
        super("优惠券已创建，活动发布结果待确认，请使用原券编号重试初始化", cause);
        this.voucherId = voucherId;
    }
}
