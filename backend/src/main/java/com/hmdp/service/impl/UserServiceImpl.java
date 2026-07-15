package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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

        // 4.保存验证码到 redis 中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

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

//        // 3.验证验证码是否合法
//        String code = loginForm.getCode();
//        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
//        if (!cacheCode.equals(code)) {
//            // 4.如果不合法，返回错误
//            return Result.fail("验证码错误");
//        }

        // 5.从数据库中查询用户是否存
        User user = query().eq("phone", phone).one();

        // 6.如果不存在，创建并保存
        if (user == null) {
            user = createNewUser(phone);
            log.debug("新用户创建成功，用户信息：{}", user);
        }

        // 7.保存用户到 redis 中（redis 中只保存用户不敏感的信息）
        // 7.1为用户生成token
        String token = UUID.fastUUID().toString(true);

        // 7.2将用户对象转换为HashMap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        // 7.3保存
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        // 7.4设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8.返回token
        return Result.ok(token);
    }

    /**
     * 登出功能
     *
     * @param token 用户令牌
     * @return 通用结果
     */
    @Override
    public Result logout(String token) {
        // 1.删除 Redis 中的用户数据
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        // 2.清除 ThreadLocal
        UserHolder.removeUser();
        return Result.ok();
    }

    /**
     * 用户签到
     *
     * @return 签到结果
     */
    @Override
    public Result sign() {
        // 1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if(user == null) return Result.fail("请先登录");
        Long userId = user.getId();

        // 2.获取当前日期
        LocalDateTime now = LocalDateTime.now();

        // 3.得到redis的key
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userId + ":" + date;

        // 4.当前天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5.签到并根据原值判断今天是否已签到
        Boolean isSign = stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        if (Boolean.TRUE.equals(isSign)) return Result.fail("今日已签到");

        // 6.返回结果
        return Result.ok();
    }

    /**
     * 获取连续签到天数
     *
     * @return 连续签到天数
     */
    @Override
    public Result getSignCount() {
        // 1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if(user == null) return Result.fail("请先登录");
        Long userId = user.getId();

        // 2.获取当前日期
        LocalDateTime now = LocalDateTime.now();

        // 3.得到redis的key
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userId + ":" + date;

        // 4.当前天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5.获取截止到今天的签到记录
        List<Long> results = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        // 5.1 判断结果是否为空，如果为空返回0
        if (results == null || results.isEmpty()) return Result.ok(0);
        // 5.2 判断返回的签到记录是否为空或者0
        Long num = results.get(0);
        if (num == null || num == 0) return Result.ok(0);

        // 6.循环遍历，统计连续签到的天数
        int count = 0;
        while((num & 1) != 0){
            num >>>= 1;
            count++;
        }
        return Result.ok(count);
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
