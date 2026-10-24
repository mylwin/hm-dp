package com.hmdp.service;

import com.hmdp.dto.SeckillAcceptance;

/**
 * 独立的可靠受理协议；第二阶段不接入旧秒杀 HTTP 入口。
 *
 * @author myl
 */
public interface ISeckillRequestService {
    /**
     * 为当前可信用户登记请求、原子预占库存，并持久化待发事件。
     *
     * @param userId    由鉴权层获取的用户编号，不接受未经校验的 HTTP 用户编号
     * @param voucherId 券编号
     * @param requestId 同一用户重试时沿用的标识，只允许字母、数字、下划线和中划线，最多 128 字符
     * @param traceId   首次请求跟踪标识，不能为空且最多 128 字符
     * @return 数据库提交后的受理状态；重复请求复用原订单编号和状态
     * @throws IllegalArgumentException                             参数非法或复用 requestId 时更换券编号
     * @throws com.hmdp.exception.SeckillAcceptancePendingException 预占或受理提交结果不确定
     */
    SeckillAcceptance accept(Long userId, Long voucherId, String requestId, String traceId);
}
