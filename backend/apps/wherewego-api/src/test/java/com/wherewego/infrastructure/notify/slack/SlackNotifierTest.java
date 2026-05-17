package com.wherewego.infrastructure.notify.slack;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.wherewego.config.env.SlackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlackNotifier.notifyFailure 를 호출할 때,")
class SlackNotifierTest {

    private static final String WEBHOOK_PATH = "/services/T000/B000/XXX";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
    }

    @Nested
    @DisplayName("webhookUri 가 비어있을 때,")
    class WhenWebhookBlank {

        @DisplayName("webhookUri 가 null 이면 호출이 스킵된다.")
        @Test
        void nullWebhook_skipsCall() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                    .willReturn(aResponse().withStatus(200)));
            SlackProperties properties = new SlackProperties("wherewego", "#alert", null);
            SlackNotifier notifier = new SlackNotifier(properties);

            // act
            notifier.notifyFailure("실패 알림", Map.of("k", "v"));

            // assert
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }

        @DisplayName("webhookUri 가 blank 이면 호출이 스킵된다.")
        @Test
        void blankWebhook_skipsCall() {
            // arrange
            wireMock.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                    .willReturn(aResponse().withStatus(200)));
            SlackProperties properties = new SlackProperties("wherewego", "#alert", "   ");
            SlackNotifier notifier = new SlackNotifier(properties);

            // act
            notifier.notifyFailure("실패 알림", Map.of("k", "v"));

            // assert
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("webhookUri 가 설정되었을 때,")
    class WhenWebhookSet {

        @DisplayName("webhookUri 가 설정되었으면 Slack 에 POST 된다.")
        @Test
        void webhookSet_postsToSlack() {
            // arrange : WireMock baseUrl 은 http://localhost:포트 — strictSchemeCheck=false 로 우회.
            wireMock.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                    .willReturn(aResponse().withStatus(200)));
            String webhookUri = wireMock.baseUrl() + WEBHOOK_PATH;
            SlackProperties properties =
                    new SlackProperties("wherewego", "#alert", webhookUri);
            SlackNotifier notifier = new SlackNotifier(properties, false);

            // act
            notifier.notifyFailure("폴백 실패", Map.of("keyword", "스타벅스"));

            // assert : 1회 POST + 본문에 title 포함
            wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo(WEBHOOK_PATH))
                    .withRequestBody(containing("폴백 실패")));
        }

        @DisplayName("webhookUri 가 http 스킴이면 호출이 스킵된다 (strict 가드).")
        @Test
        void webhookHttpScheme_skipsCall() {
            // arrange : http URL — strictSchemeCheck=true 가 거부.
            wireMock.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                    .willReturn(aResponse().withStatus(200)));
            String webhookUri = wireMock.baseUrl() + WEBHOOK_PATH; // http://...
            SlackProperties properties =
                    new SlackProperties("wherewego", "#alert", webhookUri);
            SlackNotifier notifier = new SlackNotifier(properties); // strictSchemeCheck=true

            // act
            notifier.notifyFailure("폴백 실패", Map.of("k", "v"));

            // assert
            assertThat(wireMock.getAllServeEvents()).isEmpty();
        }
    }
}
