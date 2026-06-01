package com.wherewego.infrastructure.auth.kakao;

import com.wherewego.config.env.KakaoApiProperties;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Component
public class KakaoOAuthClient {

    private final KakaoApiProperties props;
    private final RestClient tokenClient;
    private final RestClient userClient;

    public KakaoOAuthClient(
            KakaoApiProperties props,
            @Value("${kakao.oauth.token-base-url:https://kauth.kakao.com}") String tokenBaseUrl,
            @Value("${kakao.oauth.user-base-url:https://kapi.kakao.com}") String userBaseUrl
    ) {
        this.props = props;
        // kakao.callback.timeout-ms(3000) 를 connect+read 양쪽에 동일 적용 — 단일 factory 를 두 클라이언트가 공유.
        ClientHttpRequestFactory factory = buildRequestFactory(props.callback().timeoutMs());
        this.tokenClient = RestClient.builder().baseUrl(tokenBaseUrl).requestFactory(factory).build();
        this.userClient = RestClient.builder().baseUrl(userBaseUrl).requestFactory(factory).build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    public KakaoTokenResponse exchangeCodeForToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", props.oauth().clientId());
        form.add("client_secret", props.oauth().clientSecret());
        form.add("redirect_uri", props.oauth().redirectUri());
        form.add("code", code);

        try {
            return tokenClient.post()
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED,
                                "카카오 토큰 교환 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED,
                                "카카오 토큰 교환 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .body(KakaoTokenResponse.class);
        } catch (CoreException e) {
            throw e;
        } catch (RestClientException e) {
            throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "카카오 토큰 교환 중 통신 오류가 발생했습니다.");
        }
    }

    /**
     * P1: Kakao access token 의 메타정보 조회 ({@code /v1/user/access_token_info}).
     * 응답의 {@code app_id} 로 토큰이 우리 앱 발급분인지 검증하는 데 사용한다(다른 앱 토큰 오용 차단).
     * 위변조/만료 토큰 → 카카오 4xx → AUTH_KAKAO_API_FAILED(502) (fetchUserInfo 와 동일 정책).
     */
    public KakaoAccessTokenInfoResponse fetchAccessTokenInfo(String kakaoAccessToken) {
        try {
            return userClient.get()
                    .uri("/v1/user/access_token_info")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED,
                                "카카오 토큰 정보 조회 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED,
                                "카카오 토큰 정보 조회 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .body(KakaoAccessTokenInfoResponse.class);
        } catch (CoreException e) {
            throw e;
        } catch (RestClientException e) {
            throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "카카오 토큰 정보 조회 중 통신 오류가 발생했습니다.");
        }
    }

    public KakaoUserInfoResponse fetchUserInfo(String kakaoAccessToken) {
        try {
            return userClient.get()
                    .uri("/v2/user/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED,
                                "카카오 사용자 정보 조회 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED,
                                "카카오 사용자 정보 조회 실패 (status=" + res.getStatusCode() + ").");
                    })
                    .body(KakaoUserInfoResponse.class);
        } catch (CoreException e) {
            throw e;
        } catch (RestClientException e) {
            throw new CoreException(ErrorType.AUTH_KAKAO_API_FAILED, "카카오 사용자 정보 조회 중 통신 오류가 발생했습니다.");
        }
    }
}
