package com.hmdp.constants;

/**
 * 系统常量
 *
 * 保留旧聚合常量作为学习参考，新代码使用按功能拆分的常量类。
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Deprecated
public class SystemConstants {
    /**
     * 图片上传路径
     */
    public static final String IMAGE_UPLOAD_DIR = StorageConstants.IMAGE_UPLOAD_DIR;

    /**
     * 用户昵称前缀
     */
    public static final String USER_NICK_NAME_PREFIX = UserConstants.USER_NICK_NAME_PREFIX;

    /**
     * 默认页大小
     */
    public static final int DEFAULT_PAGE_SIZE = PageConstants.DEFAULT_PAGE_SIZE;

    /**
     * 最大页大小
     */
    public static final int MAX_PAGE_SIZE = PageConstants.MAX_PAGE_SIZE;

    /**
     * session 中存储用户信息的键
     */
    public static final String USER_SESSION_KEY = AuthConstants.USER_SESSION_KEY;

    /**
     * session 中存储验证码的键
     */
    public static final String CODE_SESSION_KEY = AuthConstants.CODE_SESSION_KEY;
}
