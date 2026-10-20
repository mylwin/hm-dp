package com.hmdp.controller;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.config.JwtProperties;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.LoginTokens;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.security.UserContext;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;

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

    private static final String REFRESH_COOKIE = "hmdp_refresh";

    @Autowired
    private JwtProperties jwtProperties;

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
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpServletResponse response) {
        // 实现登录功能
        return writeTokens(userService.login(loginForm), response);
    }

    /**
     * 登出功能
     *
     * @param token 用户令牌
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token,
                         @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                         @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
                         HttpServletResponse response) {
        // Cookie 撤销要求自定义头，防跨站表单；旧前端仍可用授权头退出。
        boolean cookieRequest = "XMLHttpRequest".equals(requestedWith);
        if (!cookieRequest && (token == null || token.isBlank())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return Result.fail("请求来源无效");
        }
        Result result = userService.logout(token, cookieRequest ? refreshToken : null);
        setRefreshCookie(response, "", Duration.ZERO);
        return result;
    }

    /**
     * 刷新令牌
     *
     * @param refreshToken 刷新令牌
     * @return 访问令牌
     */
    @PostMapping("/refresh")
    public Result refresh(@CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
                          @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
                          HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!"XMLHttpRequest".equals(requestedWith)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return Result.fail("请求来源无效");
        }
        return writeTokens(userService.refresh(refreshToken), response);
    }

    /**
     * 将令牌写入响应
     *
     * @param result   登录结果
     * @param response 响应
     * @return 登录结果
     */
    private Result writeTokens(Result result, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (result.getData() instanceof LoginTokens tokens) {
            Duration remaining = Duration.between(Instant.now(), tokens.refreshExpiresAt());
            setRefreshCookie(response, tokens.refreshToken(), remaining.isNegative() ? Duration.ZERO : remaining);
            // 保持 data 为访问令牌字符串，不把刷新凭证暴露给 JavaScript。
            return Result.ok(tokens.accessToken());
        }
        return result;
    }

    /**
     * 设置刷新令牌Cookie
     *
     * @param response 响应
     * @param value    刷新令牌值
     * @param maxAge   刷新令牌有效期
     */
    private void setRefreshCookie(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(jwtProperties.isRefreshCookieSecure())
                .sameSite("Lax").path("/").maxAge(maxAge).build().toString());
    }

    /**
     * 获取当前登录用户
     *
     * @return 当前登录用户
     */
    @GetMapping("/me")
    public Result me() {
        // 获取当前登录用户
        UserDTO userDTO = UserContext.getUser();
        return Result.ok(userDTO);
    }

    /**
     * 根据id获取用户信息
     *
     * @param userId 用户id
     * @return 用户信息
     */
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
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
     *
     * @param userId 用户id
     * @return 用户信息
     */
    @GetMapping("/{id}")
    public Result queryByUserId(@PathVariable("id") Long userId) {
        // 1.查询用户
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }

        // 2.返回结果
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    /**
     * 用户签到
     *
     * @return 签到结果
     */
    @PutMapping("/sign")
    public Result sign() {
        return userService.sign();
    }

    /**
     * 获取连续签到天数
     *
     * @return 连续签到天数
     */
    @GetMapping("/sign/count")
    public Result getSignCount() {
        return userService.getSignCount();
    }
}
