package com.wherewego.infrastructure.chatbot.callback;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.wherewego.infrastructure.notify.slack.SlackNotifier;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("KakaoCallbackClient.push 를 호출할 때,")
class KakaoCallbackClientTest {

    private static final String CALLBACK_PATH = "/callback";
    private static final int TIMEOUT_MS = 3_000;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    @Mock
    private SlackNotifier slackNotifier;

    // WireMock baseUrl 은 http://localhost:포트 — SSRF 가드에 의해 막히므로
    // 통합 케이스는 strictHostCheck=false 로 우회한다. SSRF 가드 자체의 검증은
    // strictHostCheck=true 인스턴스 + isAllowedCallbackUrl 단위 테스트로 분리한다.
    private KakaoCallbackClient client;
    private KakaoCallbackClient strictClient;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        client = new KakaoCallbackClient(TIMEOUT_MS, false, slackNotifier);
        strictClient = new KakaoCallbackClient(TIMEOUT_MS, true, slackNotifier);
    }

    @Nested
    @DisplayName("정상 callbackUrl 이 주어졌을 때,")
    class WhenValidUrl {

        @DisplayName("callbackUrl 로 SkillResponse JSON 이 POST 되고 200 응답을 받는다.")
        @Test
        void post_skillResponseJson_andReceives200() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(CALLBACK_PATH))
                    .willReturn(aResponse().withStatus(200)));
            String callbackUrl = wireMock.baseUrl() + CALLBACK_PATH;
            ChatbotV1Dto.SkillResponse payload = ChatbotV1Dto.SkillResponse.simple("done");

            // act
            assertThatCode(() -> client.push(callbackUrl, payload))
                    .doesNotThrowAnyException();

            // assert : 1회 POST 발생
            wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(CALLBACK_PATH)));
        }
    }

    @Nested
    @DisplayName("callbackUrl 이 비어있을 때,")
    class WhenUrlBlank {

        @DisplayName("callbackUrl 이 null 이면 호출이 스킵된다.")
        @Test
        void nullUrl_skipsCall() {
            // arrange : 임의 stub 등록 (호출되면 안 됨)
            wireMock.stubFor(post(urlPathEqualTo(CALLBACK_PATH))
                    .willReturn(aResponse().withStatus(200)));

            // act
            client.push(null, ChatbotV1Dto.SkillResponse.simple("hi"));

            // assert : WireMock 으로의 호출이 전혀 발생하지 않아야 한다.
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }

        @DisplayName("callbackUrl 이 blank 이면 호출이 스킵된다.")
        @Test
        void blankUrl_skipsCall() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(CALLBACK_PATH))
                    .willReturn(aResponse().withStatus(200)));

            // act
            client.pushText("   ", "text");

            // assert
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("원격 서버가 실패 응답을 보낼 때,")
    class WhenRemoteFails {

        @DisplayName("응답 5xx 이어도 예외를 던지지 않고 swallow 한다.")
        @Test
        void serverError_swallowed() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(CALLBACK_PATH))
                    .willReturn(aResponse().withStatus(500)));
            String callbackUrl = wireMock.baseUrl() + CALLBACK_PATH;

            // act & assert
            assertThatCode(() -> client.push(callbackUrl,
                    ChatbotV1Dto.SkillResponse.simple("done")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("SSRF 가드가 활성화된 상태에서,")
    class WhenStrictHostCheck {

        @DisplayName("http 스킴이면 호출이 스킵된다.")
        @Test
        void httpScheme_skipsCall() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(CALLBACK_PATH))
                    .willReturn(aResponse().withStatus(200)));

            // act : http URL — strictHostCheck=true 인스턴스 사용
            strictClient.pushText("http://example.com/callback", "text");

            // assert : wiremock 미호출
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }

        @DisplayName("사설 IP(127.0.0.1) 호스트이면 호출이 스킵된다.")
        @Test
        void privateHost_skipsCall() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(CALLBACK_PATH))
                    .willReturn(aResponse().withStatus(200)));

            // act : 127.0.0.1 — strictHostCheck=true 인스턴스 사용
            strictClient.pushText("https://127.0.0.1/callback", "text");

            // assert : wiremock 미호출
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }

        @DisplayName("IPv6 loopback (`https://[::1]/callback`)이면 호출이 스킵된다.")
        @Test
        void ipv6Loopback_skipsCall() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(CALLBACK_PATH))
                    .willReturn(aResponse().withStatus(200)));

            // act : ::1 — strictHostCheck=true 인스턴스 사용
            strictClient.pushText("https://[::1]/callback", "text");

            // assert : wiremock 미호출
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }

        @DisplayName("IPv6 link-local (`https://[fe80::1]/callback`)이면 호출이 스킵된다.")
        @Test
        void ipv6LinkLocal_skipsCall() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(CALLBACK_PATH))
                    .willReturn(aResponse().withStatus(200)));

            // act : fe80::1 — strictHostCheck=true 인스턴스 사용
            strictClient.pushText("https://[fe80::1]/callback", "text");

            // assert : wiremock 미호출
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }

        @DisplayName("IPv6 ULA (`https://[fc00::1]/callback`)이면 호출이 스킵된다.")
        @Test
        void ipv6Ula_skipsCall() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(CALLBACK_PATH))
                    .willReturn(aResponse().withStatus(200)));

            // act : fc00::1 — strictHostCheck=true 인스턴스 사용
            strictClient.pushText("https://[fc00::1]/callback", "text");

            // assert : wiremock 미호출
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("isAllowedCallbackUrl SSRF 가드 단위 검증")
    class AllowedUrlGuard {

        @DisplayName("https 도메인은 허용된다.")
        @Test
        void httpsDomain_allowed() {
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://example.com/cb")).isTrue();
        }

        @DisplayName("http 스킴은 거부된다.")
        @Test
        void httpScheme_rejected() {
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("http://example.com/cb")).isFalse();
        }

        @DisplayName("localhost / 사설 / 링크로컬 IP 는 거부된다.")
        @Test
        void privateHosts_rejected() {
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://localhost/cb")).isFalse();
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://127.0.0.1/cb")).isFalse();
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://10.0.0.1/cb")).isFalse();
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://192.168.1.1/cb")).isFalse();
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://169.254.169.254/cb")).isFalse();
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://172.16.0.1/cb")).isFalse();
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://172.31.255.255/cb")).isFalse();
        }

        @DisplayName("172.x 중 사설 대역 외(172.15.x, 172.32.x)는 허용된다.")
        @Test
        void publicSubrange172_allowed() {
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://172.15.0.1/cb")).isTrue();
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https://172.32.0.1/cb")).isTrue();
        }

        @DisplayName("호스트 누락/포맷 에러는 거부된다.")
        @Test
        void invalidUrl_rejected() {
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("https:///path")).isFalse();
            assertThat(KakaoCallbackClient.isAllowedCallbackUrl("not-a-url")).isFalse();
        }
    }
}
