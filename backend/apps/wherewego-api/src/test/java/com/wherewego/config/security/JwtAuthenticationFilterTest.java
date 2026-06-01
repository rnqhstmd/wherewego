package com.wherewego.config.security;

import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.JwtValidationResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1: JwtAuthenticationFilter 의 Bearer 헤더 분기 단위 검증 (FR-1, BR-1/2, AC-1~5).
 * 만료/위변조 → 401 은 EntryPoint 책임이므로 통합 테스트가 커버하고,
 * 여기서는 필터가 SecurityContext 를 세팅/클리어하는 추출·검증 분기만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(jwtTokenProvider);
    }

    private void stubValid(String token, long userId) {
        when(jwtTokenProvider.parseAccessToken(token))
                .thenReturn(new JwtValidationResult.Valid(userId, Instant.now().plusSeconds(60)));
    }

    @DisplayName("AC-1: Authorization Bearer 헤더의 유효 토큰으로 인증 컨텍스트가 세팅된다.")
    @Test
    void bearerHeader_validToken_authenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer header-token");
        stubValid("header-token", 42L);

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(42L);
        verify(filterChain).doFilter(any(), any());
    }

    @DisplayName("소문자 bearer 스킴도 대소문자 무시로 인증된다 (RFC 7235).")
    @Test
    void lowercaseBearer_validToken_authenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "bearer header-token");
        stubValid("header-token", 42L);

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(42L);
        verify(jwtTokenProvider).parseAccessToken("header-token");
    }

    @DisplayName("AC-2: 쿠키만 있는 기존 웹 요청도 동일하게 인증된다.")
    @Test
    void cookieOnly_validToken_authenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE, "cookie-token"));
        stubValid("cookie-token", 7L);

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(7L);
    }

    @DisplayName("AC-3: 헤더와 쿠키가 동시에 있으면 헤더 토큰이 우선한다.")
    @Test
    void headerAndCookie_headerWins() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer header-token");
        request.setCookies(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE, "cookie-token"));
        stubValid("header-token", 100L);

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(100L);
        verify(jwtTokenProvider).parseAccessToken("header-token");
        verify(jwtTokenProvider, never()).parseAccessToken("cookie-token");
    }

    @DisplayName("빈 Bearer 헤더는 쿠키로 폴백한다 (웹 회귀 0).")
    @Test
    void emptyBearer_fallsBackToCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ");
        request.setCookies(new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE, "cookie-token"));
        stubValid("cookie-token", 9L);

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(9L);
        verify(jwtTokenProvider).parseAccessToken("cookie-token");
    }

    @DisplayName("AC-4: 만료된 Bearer 토큰이면 컨텍스트가 비워진 채 체인이 계속된다 (EntryPoint 가 401).")
    @Test
    void expiredBearer_clearsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer expired-token");
        when(jwtTokenProvider.parseAccessToken("expired-token"))
                .thenReturn(JwtValidationResult.Invalid.EXPIRED);

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(any(), any());
    }

    @DisplayName("AC-5: 위변조 Bearer 토큰이면 컨텍스트가 비워진 채 체인이 계속된다.")
    @Test
    void tamperedBearer_clearsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer tampered-token");
        when(jwtTokenProvider.parseAccessToken("tampered-token"))
                .thenReturn(JwtValidationResult.Invalid.INVALID_SIGNATURE);

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @DisplayName("헤더도 쿠키도 없으면 인증 없이 체인이 계속된다 (BR-1).")
    @Test
    void noTokenAtAll_passesThroughUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(any(), any());
        verify(jwtTokenProvider, never()).parseAccessToken(any());
    }
}
