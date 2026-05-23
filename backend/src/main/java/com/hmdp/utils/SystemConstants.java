package com.hmdp.utils;

/**
 * 系统常量
 *
 * @author mylwin
 * @since 2026-05-23
 */
public class SystemConstants {
    /**
     * 图片上传路径
     */
    public static final String IMAGE_UPLOAD_DIR = "D:\\lesson\\nginx-1.18.0\\html\\hmdp\\imgs\\";

    /**
     * 用户昵称前缀
     */
    public static final String USER_NICK_NAME_PREFIX = "user_";

    /**
     * 默认页大小
     */
    public static final int DEFAULT_PAGE_SIZE = 5;

    /**
     * 最大页大小
     */
    public static final int MAX_PAGE_SIZE = 10;

    /**
     * session 中存储用户信息的键
     */
    public static final String USER_SESSION_KEY = "user";

    /**
     * session 中存储验证码的键
     */
    public static final String CODE_SESSION_KEY = "code";
}
