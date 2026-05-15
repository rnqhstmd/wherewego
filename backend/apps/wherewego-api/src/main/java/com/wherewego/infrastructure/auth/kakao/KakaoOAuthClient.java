package com.wherewego.infrastructure.auth.kakao;

import com.wherewego.config.env.KakaoApiProperties;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
        this.tokenClient = RestClient.builder().baseUrl(tokenBaseUrl).build();
        this.userClient = RestClient.builder().baseUrl(userBaseUrl).build();
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
