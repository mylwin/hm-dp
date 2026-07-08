package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 关注控制器
 *
 * @author mylwin
 * @since 2026-05-23
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    private IFollowService followService;

    /**
     * 关注
     *
     * @param followUserId 被关注用户id
     * @param isFollow 是否关注 true-关注 false-取消关注
     * @return 结果
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 判断是否关注
     *
      * @param followUserId 被关注用户id
     * @return 结果
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /**
     * 获取共同关注人列表
     *
     * @param id 用户id
     * @return 结果
     */
    @GetMapping("/common/{id}")
    public Result queryCommonFollow(@PathVariable("id") Long id) {
        return followService.queryCommonFollow(id);
    }
}
