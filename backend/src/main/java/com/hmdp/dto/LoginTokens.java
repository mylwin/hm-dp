package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * 内部签发结果，刷新凭证只由控制器写入 HttpOnly Cookie。
 */
public record LoginTokens(String accessToken, @JsonIgnore String refreshToken,
                          @JsonIgnore Instant refreshExpiresAt) {
}
