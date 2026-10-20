package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置拦截器
 *
 * @author mylwin
 * @since 2026-05-23
 */
@Configuration
@RequiredArgsConstructor
public class SpringMvcConfig implements WebMvcConfigurer {
    private final JwtAuthInterceptor jwtAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登出由服务自行解析令牌，确保无令牌和无效令牌也能幂等退出。
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/user/logout", "/user/refresh", "/user/login", "/user/code").order(0);

        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login",
                        "/user/code",
                        "/user/logout",
                        "/user/refresh",
                        "/shop/**",
                        "/voucher/list/**",
                        "/shop-type/**",
                        "/blog/hot",
                        "/blog/*/likes").order(1);
    }
}
