package com.hmdp.dto;

import lombok.Data;


/**
 * 用户DTO
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Data
public class UserDTO {
    /**
     * 用户id
     */
    private Long id;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户头像
     */
    private String icon;
}
