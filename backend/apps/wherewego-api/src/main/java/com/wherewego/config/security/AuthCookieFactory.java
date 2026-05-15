package com.wherewego.config.security;

import com.wherewego.config.env.JwtProperties;
import com.wherewego.config.env.WebSecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthCookieFactory {

    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";

    private final JwtProperties jwtProperties;
    private final WebSecurityProperties webSecurityProperties;

    public ResponseCookie accessCookie(String token) {
        return baseBuilder(ACCESS_TOKEN, token, jwtProperties.accessTtlSeconds()).build();
    }

    public ResponseCookie refreshCookie(String token) {
        return baseBuilder(REFRESH_TOKEN, token, jwtProperties.refreshTtlSeconds()).build();
    }

    public ResponseCookie expiredAccessCookie() {
        return baseBuilder(ACCESS_TOKEN, "", 0L).build();
    }

    public ResponseCookie expiredRefreshCookie() {
        return baseBuilder(REFRESH_TOKEN, "", 0L).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String name, String value, long maxAgeSeconds) {
        WebSecurityProperties.Cookie cfg = webSecurityProperties.cookie();
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cfg.secure())
                .sameSite(cfg.sameSite())
                .path("/")
                .maxAge(maxAgeSeconds);
        if (cfg.domain() != null && !cfg.domain().isBlank()) {
            b.domain(cfg.domain());
        }
        return b;
    }
}
