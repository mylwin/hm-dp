package com.hmdp.dto;

import lombok.Data;

/**
 * 登录表单DTO
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Data
public class LoginFormDTO {
    /**
     * 手机号
     */
    private String phone;
    /**
     * 验证码
     */
    private String code;
    /**
     * 密码
     */
    private String password;
}
