package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import jakarta.servlet.http.HttpSession;

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
     * @param session 会话
     * @return 通用结果
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session 会话
     * @return 通用结果
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 登出功能
     * @param token 用户令牌
     * @return 通用结果
     */
    Result logout(String token);
}
