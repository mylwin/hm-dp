package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

import static com.hmdp.utils.SystemConstants.USER_SESSION_KEY;

/**
 * 登录拦截器
 *
 * @author mylwin
 * @since 2025-05-23
 */
public class LoginInterceptor implements HandlerInterceptor {
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
        // 1.获取请求头中的 session
        HttpSession session = request.getSession();

        // 2.从 session 中获取用户
        Object userDTO = session.getAttribute(USER_SESSION_KEY);

        // 3.如果用户不存在，返回未登录状态码
        if (userDTO == null){
            response.setStatus(401);
            return false;
        }

        // 4.用户存在，保存到 ThreadLocal 中
        UserHolder.saveUser((UserDTO) userDTO);

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
