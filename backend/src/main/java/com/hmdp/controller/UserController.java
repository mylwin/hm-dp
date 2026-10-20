package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;



/**
 * 用户控制器
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private IUserService userService;

    @Autowired
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm){
        // 实现登录功能
        return userService.login(loginForm);
    }

    /**
     * 登出功能
     * @param token 用户令牌
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token){
        return userService.logout(token);
    }

    /**
     * 获取当前登录用户
     * @return 当前登录用户
     */
    @GetMapping("/me")
    public Result me(){
        // 获取当前登录用户
        UserDTO userDTO = UserHolder.getUser();
        return Result.ok(userDTO);
    }

    /**
     * 根据id获取用户信息
     * @param userId 用户id
     * @return 用户信息
     */
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /**
     * 根据id获取用户信息
     * @param userId 用户id
     * @return 用户信息
     */
    @GetMapping("/{id}")
    public Result queryByUserId(@PathVariable("id") Long userId){
        // 1.查询用户
        User user = userService.getById(userId);
        if(user == null){
            return Result.ok();
        }

        // 2.返回结果
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    /**
     * 用户签到
     * @return 签到结果
     */
    @PutMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    /**
     * 获取连续签到天数
     * @return 连续签到天数
     */
    @GetMapping("/sign/count")
    public Result getSignCount(){
        return userService.getSignCount();
    }
}
