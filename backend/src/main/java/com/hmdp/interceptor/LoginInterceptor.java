package com.hmdp.interceptor;

import com.hmdp.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

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
        // 1.判断ThreadLocal中是否有用户
        if (UserContext.getUser() == null) {
            // 不存在则返回未登录状态码
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
