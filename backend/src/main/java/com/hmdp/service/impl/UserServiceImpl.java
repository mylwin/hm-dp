package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.SystemConstants.*;

/**
 * 用户服务实现类
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 发送手机验证码
     *
     * @param phone   手机号
     * @param session 会话
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.验证手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不合法，返回错误
            return Result.fail("手机号格式错误");
        }

        // 3.如果合法，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 session 中
        session.setAttribute(CODE_SESSION_KEY, code);

        // 5.发送验证码
        log.debug("发送验证码成功，验证码：{}", code);

        // 6.返回结果
        return Result.ok();
    }

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session   会话
     * @return 通用结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.验证手机号是否合法
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不合法，返回错误
            return Result.fail("手机号格式错误");
        }

        // 3.验证验证码是否合法
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute(CODE_SESSION_KEY);
        if (code == null || !cacheCode.toString().equals(code)) {
            // 4.如果不合法，返回错误
            return Result.fail("验证码错误");
        }

        // 5.从数据库中查询用户是否存在
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, phone);
        User user = getOne(queryWrapper);

        // 6.如果不存在，创建并保存
        if (user == null) {
            user = createNewUser(phone);
            log.debug("新用户创建成功，用户信息：{}", user);
        }

        // 7.保存用户到 session 中（session 中只保存用户不敏感的信息）
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        session.setAttribute(USER_SESSION_KEY, userDTO);
        log.debug("用户登录成功，用户信息：{}", userDTO);
        return Result.ok(userDTO);
    }

    /**
     * 登出功能
     *
     * @param session 会话
     * @return 通用结果
     */
    @Override
    public Result logout(HttpSession session) {
        session.invalidate();
        return Result.ok();
    }

    /**
     * 创建新用户
     *
     * @param phone 手机号
     * @return 用户
     */
    private User createNewUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
