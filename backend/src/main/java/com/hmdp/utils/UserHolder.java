package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 用户上下文持有者
 *
 * @author mylwin
 * @since 2026-05-23
 */
public class UserHolder {
    /**
     * 存储当前登录用户
     */
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    /**
     * 保存当前登录用户
     *
     * @param user 登录用户
     */
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    /**
     * 获取当前登录用户
     *
     * @return 登录用户
     */
    public static UserDTO getUser(){
        return tl.get();
    }

    /**
     * 移除当前登录用户
     */
    public static void removeUser(){
        tl.remove();
    }
}
