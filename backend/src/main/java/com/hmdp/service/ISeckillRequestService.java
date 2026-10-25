package com.hmdp.service;

import com.hmdp.dto.SeckillAcceptance;
import com.hmdp.dto.SeckillRequestResult;

/**
 * 可靠受理与结果查询协议，与旧秒杀入口独立。
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

    /**
     * 按订单号查询当前用户的抢购进度。
     * @param userId 当前可信用户编号
     * @param orderId 订单编号，必须为正数
     * @return 抢购结果，不存在或无权访问时返回 null
     * @throws IllegalArgumentException 编号非法
     */
    SeckillRequestResult queryByOrder(Long userId, Long orderId);

    /**
     * 按原请求编号查询进度，适用于提交响应丢失的情况。
     * @param userId 当前可信用户编号
     * @param requestId 原客户端请求编号
     * @return 抢购结果，不存在时返回 null
     * @throws IllegalArgumentException 用户或请求编号非法
     */
    SeckillRequestResult queryByRequest(Long userId, String requestId);
}
