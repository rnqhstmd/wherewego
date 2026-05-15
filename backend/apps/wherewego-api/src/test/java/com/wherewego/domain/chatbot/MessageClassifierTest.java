package com.wherewego.domain.chatbot;

import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageClassifierTest {

    @Mock
    private TwoSecondMemoSession twoSecondMemoSession;

    @InjectMocks
    private MessageClassifier messageClassifier;

    private static ChatbotV1Dto.SkillRequest request(String utterance, Map<String, String> params) {
        return request(utterance, params, null);
    }

    private static ChatbotV1Dto.SkillRequest request(String utterance,
                                                     Map<String, String> params,
                                                     Map<String, String> clientExtra) {
        ChatbotV1Dto.UserRequest userRequest = new ChatbotV1Dto.UserRequest(
                utterance, new ChatbotV1Dto.User("kakao-user-1", "botUserKey")
        );
        ChatbotV1Dto.Action action = new ChatbotV1Dto.Action(params, clientExtra);
        return new ChatbotV1Dto.SkillRequest(userRequest, action);
    }

    @DisplayName("메시지를 분류할 때,")
    @Nested
    class Classify {

        @DisplayName("action.params 에 placeId 가 있으면, PLACE_SELECTION 으로 분류된다.")
        @Test
        void classify_actionParamsHavePlaceId_returnsPlaceSelection() {
            // arrange
            ChatbotV1Dto.SkillRequest req = request("ignored", Map.of("placeId", "abc-123"));

            // act
            MessageType type = messageClassifier.classify(req, "kakao-user-1");

            // assert
            assertThat(type).isEqualTo(MessageType.PLACE_SELECTION);
        }

        @DisplayName("action.clientExtra 에 placeId 가 있으면, PLACE_SELECTION 으로 분류된다 (카카오 i 오픈빌더 버튼 action=message extra 전달 경로).")
        @Test
        void classify_actionClientExtraHavePlaceId_returnsPlaceSelection() {
            // arrange
            ChatbotV1Dto.SkillRequest req = request("ignored", Map.of(), Map.of("placeId", "abc-123"));

            // act
            MessageType type = messageClassifier.classify(req, "kakao-user-1");

            // assert
            assertThat(type).isEqualTo(MessageType.PLACE_SELECTION);
        }

        @DisplayName("utterance 가 6자리 숫자면, LINK_CODE 로 분류된다.")
        @Test
        void classify_utteranceIs6Digits_returnsLinkCode() {
            // arrange
            ChatbotV1Dto.SkillRequest req = request("123456", Map.of());

            // act
            MessageType type = messageClassifier.classify(req, "kakao-user-1");

            // assert
            assertThat(type).isEqualTo(MessageType.LINK_CODE);
        }

        @DisplayName("utterance 가 인스타그램 URL 이면, INSTAGRAM_LINK 로 분류된다.")
        @Test
        void classify_utteranceIsInstagramUrl_returnsInstagramLink() {
            // arrange
            ChatbotV1Dto.SkillRequest req = request("https://www.instagram.com/p/ABC123/", Map.of());

            // act
            MessageType type = messageClassifier.classify(req, "kakao-user-1");

            // assert
            assertThat(type).isEqualTo(MessageType.INSTAGRAM_LINK);
        }

        @DisplayName("위 셋이 아니지만 peek 결과가 있으면, TEXT_2SEC_CANDIDATE 로 분류된다.")
        @Test
        void classify_textWithPeekHit_returnsText2Sec() {
            // arrange
            ChatbotV1Dto.SkillRequest req = request("맛있어요", Map.of());
            when(twoSecondMemoSession.peek("kakao-user-1")).thenReturn(Optional.of(42L));

            // act
            MessageType type = messageClassifier.classify(req, "kakao-user-1");

            // assert
            assertThat(type).isEqualTo(MessageType.TEXT_2SEC_CANDIDATE);
        }

        @DisplayName("peek 결과가 비어있으면, UNKNOWN 으로 분류된다 (FR-MEMO-4).")
        @Test
        void classify_textWithoutPeek_returnsUnknown() {
            // arrange
            ChatbotV1Dto.SkillRequest req = request("그냥 텍스트", Map.of());
            when(twoSecondMemoSession.peek("kakao-user-1")).thenReturn(Optional.empty());

            // act
            MessageType type = messageClassifier.classify(req, "kakao-user-1");

            // assert
            assertThat(type).isEqualTo(MessageType.UNKNOWN);
        }

        @DisplayName("placeId 가 있으면 6자리 utterance 여도 PLACE_SELECTION 이 우선이다.")
        @Test
        void classify_priorityOrder() {
            // arrange
            ChatbotV1Dto.SkillRequest req = request("123456", Map.of("placeId", "abc-123"));

            // act
            MessageType type = messageClassifier.classify(req, "kakao-user-1");

            // assert
            assertThat(type).isEqualTo(MessageType.PLACE_SELECTION);
        }
    }
}
