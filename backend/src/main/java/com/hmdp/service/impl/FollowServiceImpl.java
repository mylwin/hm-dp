package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * 关注服务实现类
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    /**
     * 判断是否关注
     *
     * @param followUserId 被关注用户id
     * @return 结果
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        // 2.判断是否关注
        Long count = query().eq("user_id", user.getId()).eq("follow_user_id", followUserId).count();

        // 3.返回结果
        if (count == null) {
            return Result.fail("系统错误");
        }
        return Result.ok(count > 0);
    }

    /**
     * 关注
     *
     * @param followUserId 被关注用户id
     * @param isFollow     是否关注该用户 true-关注 false-取消关注
     * @return 结果
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        // 2.判断是否关注该用户
        if (isFollow) {
            // 3.关注该用户，插入记录
            Follow flow = new Follow();
            flow.setUserId(user.getId());
            flow.setFollowUserId(followUserId);
            save(flow);
        } else {
            // 4.取消关注该用户，删除记录
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getUserId, user.getId()).eq(Follow::getFollowUserId, followUserId);
            remove(queryWrapper);
        }

        // 5.返回结果
        return Result.ok();
    }
}
