package com.wherewego.infrastructure.gemini;

import com.wherewego.infrastructure.gemini.GeminiPlaceClient.GeminiGenerateContentResponse;
import com.wherewego.infrastructure.gemini.GeminiPlaceClient.GeminiGenerateContentResponse.Candidate;
import com.wherewego.infrastructure.gemini.GeminiPlaceClient.GeminiGenerateContentResponse.Content;
import com.wherewego.infrastructure.gemini.GeminiPlaceClient.GeminiGenerateContentResponse.Part;
import com.wherewego.infrastructure.gemini.GeminiPlaceClient.ParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeminiPlaceClient#parsePlaceName} 단위 테스트.
 *
 * <p>외부 HTTP 호출 없이 응답 파싱 로직(따옴표 제거, 공백 정규화, literal "null" 판별, 일시 장애 감지)을
 * 직접 검증한다. WireMock 기반 HTTP 계약 테스트는 BASE_URL 외부화 작업과 함께 Phase 2.6에서 별도 처리한다.</p>
 */
class GeminiPlaceClientParsingTest {

    private static GeminiGenerateContentResponse responseOf(String text) {
        return new GeminiGenerateContentResponse(
                List.of(new Candidate(new Content(List.of(new Part(text)))))
        );
    }

    @Nested
    @DisplayName("성공 케이스 (cacheable=true)")
    class SuccessCases {

        @Test
        @DisplayName("따옴표로 감싼 장소명 응답 → 따옴표 제거 후 추출")
        void quotedPlaceName() {
            GeminiGenerateContentResponse response = responseOf("\"스타벅스\"");

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).contains("스타벅스");
            assertThat(result.cacheable()).isTrue();
        }

        @Test
        @DisplayName("따옴표 없는 장소명 응답 → 그대로 추출")
        void plainPlaceName() {
            GeminiGenerateContentResponse response = responseOf("스타벅스");

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).contains("스타벅스");
            assertThat(result.cacheable()).isTrue();
        }

        @Test
        @DisplayName("줄바꿈 포함 응답 → 공백으로 정규화")
        void normalizeNewlines() {
            GeminiGenerateContentResponse response = responseOf("\"스타벅스\n강남\"");

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).contains("스타벅스 강남");
            assertThat(result.cacheable()).isTrue();
        }
    }

    @Nested
    @DisplayName("literal \"null\" 응답 (cacheable=true, empty)")
    class LiteralNullCases {

        @Test
        @DisplayName("응답 텍스트가 \"null\" → empty + cacheable=true")
        void literalNullText() {
            GeminiGenerateContentResponse response = responseOf("null");

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).isEmpty();
            assertThat(result.cacheable()).isTrue();
        }

        @Test
        @DisplayName("따옴표로 감싼 \"null\" → empty + cacheable=true")
        void quotedNullText() {
            GeminiGenerateContentResponse response = responseOf("\"null\"");

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).isEmpty();
            assertThat(result.cacheable()).isTrue();
        }

        @Test
        @DisplayName("대소문자 혼합 \"NULL\" → empty + cacheable=true")
        void mixedCaseNullText() {
            GeminiGenerateContentResponse response = responseOf("NULL");

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).isEmpty();
            assertThat(result.cacheable()).isTrue();
        }
    }

    @Nested
    @DisplayName("일시 장애 (cacheable=false, empty)")
    class TransientFailureCases {

        @Test
        @DisplayName("response 자체가 null → empty + cacheable=false")
        void nullResponse() {
            ParseResult result = GeminiPlaceClient.parsePlaceName(null);

            assertThat(result.value()).isEmpty();
            assertThat(result.cacheable()).isFalse();
        }

        @Test
        @DisplayName("candidates 가 null → empty + cacheable=false")
        void nullCandidates() {
            GeminiGenerateContentResponse response = new GeminiGenerateContentResponse(null);

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).isEmpty();
            assertThat(result.cacheable()).isFalse();
        }

        @Test
        @DisplayName("candidates 가 empty → empty + cacheable=false")
        void emptyCandidates() {
            GeminiGenerateContentResponse response =
                    new GeminiGenerateContentResponse(Collections.emptyList());

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).isEmpty();
            assertThat(result.cacheable()).isFalse();
        }

        @Test
        @DisplayName("parts 가 empty (SAFETY 차단 등) → empty + cacheable=false")
        void emptyParts() {
            GeminiGenerateContentResponse response = new GeminiGenerateContentResponse(
                    List.of(new Candidate(new Content(Collections.emptyList())))
            );

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).isEmpty();
            assertThat(result.cacheable()).isFalse();
        }

        @Test
        @DisplayName("part.text 가 null → empty + cacheable=false")
        void nullText() {
            GeminiGenerateContentResponse response = responseOf(null);

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).isEmpty();
            assertThat(result.cacheable()).isFalse();
        }

        @Test
        @DisplayName("part.text 가 공백만 포함 → empty + cacheable=false")
        void blankText() {
            GeminiGenerateContentResponse response = responseOf("   \n  ");

            ParseResult result = GeminiPlaceClient.parsePlaceName(response);

            assertThat(result.value()).isEmpty();
            assertThat(result.cacheable()).isFalse();
        }
    }
}
