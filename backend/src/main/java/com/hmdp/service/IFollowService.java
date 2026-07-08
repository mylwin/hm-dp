package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 关注服务接口
 *
 * @author mylwin
 * @since 2026-05-23
 */
public interface IFollowService extends IService<Follow> {
    /**
     * 判断是否关注
     *
     * @param followUserId 被关注用户id
     * @return 结果
     */
    Result isFollow(Long followUserId);

    /**
     * 关注
     *
     * @param followUserId 被关注用户id
     * @param isFollow     是否关注 true-关注 false-取消关注
     * @return 结果
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 获取共同关注人列表
     * @param id 用户id
     * @return 共同关注人列表
     */
    Result queryCommonFollow(Long id);
}
