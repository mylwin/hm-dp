package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.SystemConstants.USER_SESSION_KEY;

/**
 * 登录拦截器
 *
 * @author mylwin
 * @since 2025-05-23
 */
public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 在请求处理之前进行拦截
     *
     * @param request  请求对象
     * @param response 响应对象
     * @param handler  处理器
     * @return 是否继续处理请求
     * @throws Exception 异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求中的token
        String token = request.getHeader("authorization");

        // 2.从 redis 中获取用户
        Map<Object, Object> user = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);

        // 3.如果用户不存在，返回未登录状态码
        if (user.isEmpty()){
            response.setStatus(401);
            return false;
        }

        // 4.用户存在，保存到 ThreadLocal 中
        // 4.1将用户由Map转换为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(user, new UserDTO(), false);

        // 4.2保存用户信息到TheadLocal中
        UserHolder.saveUser(userDTO);

        // 5.放行
        return true;
    }

    /**
     * 在请求处理之后进行拦截
     *
     * @param request  请求对象
     * @param response 响应对象
     * @param handler  处理器
     * @param ex       异常
     * @throws Exception 异常
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 1.移除用户
        UserHolder.removeUser();
    }
}
