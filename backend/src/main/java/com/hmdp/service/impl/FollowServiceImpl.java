package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_USER_KEY;

/**
 * 关注服务实现类
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

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
        String key = FOLLOW_USER_KEY + user.getId();
        if (isFollow) {
            // 3.关注该用户，插入记录
            Follow flow = new Follow();
            flow.setUserId(user.getId());
            flow.setFollowUserId(followUserId);
            boolean success = save(flow);

            // 4.保存关注用户的id到redis的set中
            if(success) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 5.取消关注该用户，删除记录
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getUserId, user.getId()).eq(Follow::getFollowUserId, followUserId);
            boolean removed = remove(queryWrapper);

            // 6.从redis的set中删除关注用户的id
            if (removed) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }

        // 5.返回结果
        return Result.ok();
    }

    /**
     * 获取共同关注人列表
     *
     * @param id 用户id
     * @return 结果
     */
    @Override
    public Result queryCommonFollow(Long id) {
        // 1.获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();

        // 2.查询当前登录用户和指定用户共同关注的用户
        String key1 = FOLLOW_USER_KEY + userId;
        String key2 = FOLLOW_USER_KEY + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);

        // 3.如果共同关注的用户不存在，返回空列表
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 4.解析出共同用户id
        List<Long> commonUserIds = intersect.stream().map(Long::valueOf).toList();

        // 5.查询共同关注的用户信息并返回
        List<UserDTO> commonUserDTOs = userService.listByIds(commonUserIds)
                .stream()
                .map(commonUser -> BeanUtil.copyProperties(commonUser, UserDTO.class))
                .toList();
        return Result.ok(commonUserDTOs);
    }
}
