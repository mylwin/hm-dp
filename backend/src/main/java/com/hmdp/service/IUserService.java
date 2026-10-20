package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

/**
 * 用户服务接口
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface IUserService extends IService<User> {
    /**
     * 发送手机验证码
     *
     * @param phone 手机号
     * @return 通用结果
     */
    Result sendCode(String phone);

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @return 通用结果
     */
    Result login(LoginFormDTO loginForm);

    /**
     * 登出功能
     *
     * @param token        用户令牌
     * @param refreshToken HttpOnly Cookie 中的刷新凭证，可为空
     * @return 通用结果
     */
    Result logout(String token, String refreshToken);

    /**
     * 轮换刷新凭证并签发新的短期访问令牌。
     *
     * @param refreshToken 刷新凭证
     * @return 通用结果
     */
    Result refresh(String refreshToken);

    /**
     * 用户签到
     *
     * @return 签到结果
     */
    Result sign();

    /**
     * 获取连续签到天数
     *
     * @return 连续签到天数
     */
    Result getSignCount();
}
