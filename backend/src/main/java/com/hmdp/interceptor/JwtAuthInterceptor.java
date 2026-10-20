package com.hmdp.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.security.JwtTokenService;
import com.hmdp.security.LoginSessionStore;
import com.hmdp.security.UserContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** JWT 本地验签与 Redis 会话鉴权，是否允许匿名由 LoginInterceptor 决定。 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {
    private final JwtTokenService jwtTokenService;
    private final LoginSessionStore loginSessionStore;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        UserContext.removeUser();
        String token = JwtTokenService.stripBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!StringUtils.hasText(token)) return true;

        try {
            Claims claims = jwtTokenService.parseToken(token);
            if (!loginSessionStore.isActive(claims)) {
                return reject(response, "登录已失效");
            }
            UserDTO user = new UserDTO();
            user.setId(jwtTokenService.getUserId(claims));
            user.setNickName(claims.get("nickName", String.class));
            user.setIcon(claims.get("icon", String.class));
            UserContext.saveUser(user);
            return true;
        } catch (JwtException e) {
            return reject(response, "请先登录");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.removeUser();
    }

    private boolean reject(HttpServletResponse response, String message) throws IOException {
        UserContext.removeUser();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.fail(message));
        return false;
    }
}
