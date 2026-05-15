package com.wherewego.interfaces.api.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.application.auth.KakaoLoginUrlInfo;
import com.wherewego.config.security.AuthCookieFactory;
import com.wherewego.domain.auth.AuthService;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.JwtValidationResult;
import com.wherewego.interfaces.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthV1Controller implements AuthV1ApiSpec {

    private final AuthService authService;
    private final AuthCookieFactory cookieFactory;
    private final JwtTokenProvider jwtTokenProvider;

    @GetMapping("/kakao/login-url")
    @Override
    public ApiResponse<AuthV1Dto.LoginUrlResponse> getKakaoLoginUrl() {
        KakaoLoginUrlInfo info = authService.getKakaoLoginUrl();
        return ApiResponse.success(AuthV1Dto.LoginUrlResponse.from(info));
    }

    @PostMapping("/kakao/callback")
    @Override
    public ResponseEntity<ApiResponse<AuthV1Dto.UserResponse>> kakaoCallback(
            @Valid @RequestBody AuthV1Dto.KakaoCallbackRequest request
    ) {
        AuthResultInfo result = authService.loginWithKakao(request.code());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.accessCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.refreshCookie(result.refreshToken()).toString())
                .body(ApiResponse.success(AuthV1Dto.UserResponse.from(result)));
    }

    @PostMapping("/token/refresh")
    @Override
    public ResponseEntity<ApiResponse<Object>> refreshToken(
            @CookieValue(name = "refresh_token", required = false) String refreshToken
    ) {
        AuthResultInfo result = authService.refreshTokens(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.accessCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.refreshCookie(result.refreshToken()).toString())
                .body(ApiResponse.success());
    }

    @PostMapping("/logout")
    @Override
    public ResponseEntity<ApiResponse<Object>> logout(
            @CookieValue(name = "access_token", required = false) String accessToken
    ) {
        if (accessToken != null && !accessToken.isBlank()) {
            JwtValidationResult result = jwtTokenProvider.parseAccessToken(accessToken);
            if (result instanceof JwtValidationResult.Valid valid) {
                authService.logout(valid.userId());
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredAccessCookie().toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredRefreshCookie().toString())
                .body(ApiResponse.success());
    }
}
