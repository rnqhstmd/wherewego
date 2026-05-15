package com.wherewego.domain.auth.kakao;

import com.wherewego.config.env.KakaoApiProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class KakaoLoginUrlGenerator {

    private static final String KAKAO_AUTHORIZE_URL = "https://kauth.kakao.com/oauth/authorize";

    private final KakaoApiProperties props;

    public KakaoLoginUrlGenerator(KakaoApiProperties props) {
        this.props = props;
    }

    public String generate() {
        return UriComponentsBuilder.fromUriString(KAKAO_AUTHORIZE_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", props.oauth().clientId())
                .queryParam("redirect_uri", props.oauth().redirectUri())
                .build()
                .encode()
                .toUriString();
    }
}
