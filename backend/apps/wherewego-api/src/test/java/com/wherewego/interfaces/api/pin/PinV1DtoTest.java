package com.wherewego.interfaces.api.pin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.pin.PinUpdateCommand;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PinV1Dto.UpdatePinRequest#toCommand()} 의 JSON 키 구분 동작 단위 테스트.
 *
 * <p>"키 없음 vs JSON null vs 빈 문자열" 을 구분해 잠금 해제(BR-8) 의도를 보존해야 한다.</p>
 */
class PinV1DtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PinV1Dto.UpdatePinRequest parse(String json) throws Exception {
        return objectMapper.readValue(json, PinV1Dto.UpdatePinRequest.class);
    }

    @DisplayName("memo 키가 아예 없을 때,")
    @Nested
    class MemoKeyAbsent {

        @DisplayName("memoProvided=false, tagProvided=false 로 PIN_UPDATE_EMPTY (AC-9).")
        @Test
        void emptyJson_throwsPinUpdateEmpty() throws Exception {
            // arrange
            PinV1Dto.UpdatePinRequest req = parse("{}");

            // act & assert
            assertThatThrownBy(req::toCommand)
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_UPDATE_EMPTY);
        }
    }

    @DisplayName("memo 키 값이 JSON null 일 때,")
    @Nested
    class MemoJsonNull {

        @DisplayName("memoProvided=false 로 간주되어 PIN_UPDATE_EMPTY (AC-9).")
        @Test
        void memoNull_throwsPinUpdateEmpty() throws Exception {
            // arrange
            PinV1Dto.UpdatePinRequest req = parse("{\"memo\": null}");

            // act & assert
            assertThatThrownBy(req::toCommand)
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_UPDATE_EMPTY);
        }
    }

    @DisplayName("memo 키 값이 빈 문자열일 때,")
    @Nested
    class MemoEmptyString {

        @DisplayName("memoProvided=true, memo=\"\" 로 잠금 해제 의도를 전달한다 (AC-11, BR-8).")
        @Test
        void memoEmptyString_buildsCommandWithEmpty() throws Exception {
            // arrange
            PinV1Dto.UpdatePinRequest req = parse("{\"memo\": \"\"}");

            // act
            PinUpdateCommand cmd = req.toCommand();

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEmpty();
            assertThat(cmd.tagProvided()).isFalse();
        }
    }

    @DisplayName("memo 키 값이 일반 문자열일 때,")
    @Nested
    class MemoNormalString {

        @DisplayName("memoProvided=true 와 해당 문자열로 커맨드가 생성된다 (AC-7).")
        @Test
        void memoNormalString_buildsCommand() throws Exception {
            // arrange
            PinV1Dto.UpdatePinRequest req = parse("{\"memo\": \"hello\"}");

            // act
            PinUpdateCommand cmd = req.toCommand();

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo("hello");
            assertThat(cmd.tagProvided()).isFalse();
        }
    }

    @DisplayName("tag 만 제공되었을 때,")
    @Nested
    class TagOnly {

        @DisplayName("tagProvided=true, tag=PLACE 로 커맨드가 생성된다 (AC-8).")
        @Test
        void tagOnly_buildsCommand() throws Exception {
            // arrange
            PinV1Dto.UpdatePinRequest req = parse("{\"tag\": \"PLACE\"}");

            // act
            PinUpdateCommand cmd = req.toCommand();

            // assert
            assertThat(cmd.memoProvided()).isFalse();
            assertThat(cmd.tagProvided()).isTrue();
            assertThat(cmd.tag()).isEqualTo(PinTag.PLACE);
        }
    }

    @DisplayName("tag 키 값이 유효하지 않은 enum 값일 때,")
    @Nested
    class TagInvalid {

        @DisplayName("PIN_TAG_INVALID 가 발생한다 (AC-13).")
        @Test
        void tagInvalidEnum_throwsPinTagInvalid() throws Exception {
            // arrange
            PinV1Dto.UpdatePinRequest req = parse("{\"tag\": \"INVALID\"}");

            // act & assert
            assertThatThrownBy(req::toCommand)
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_TAG_INVALID);
        }
    }

    @DisplayName("memo 가 비문자열 노드일 때,")
    @Nested
    class MemoNonTextual {

        @DisplayName("memo 가 숫자 노드 {\"memo\": 123} 이면 PIN_MEMO_INVALID 발생 (보안 강화).")
        @Test
        void memoNumber_throwsPinMemoInvalid() throws Exception {
            // arrange
            PinV1Dto.UpdatePinRequest req = parse("{\"memo\": 123}");

            // act & assert
            assertThatThrownBy(req::toCommand)
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_MEMO_INVALID);
        }
    }

    @DisplayName("tag 가 비문자열 노드일 때,")
    @Nested
    class TagNonTextual {

        @DisplayName("tag 가 숫자 노드 {\"tag\": 1} 이면 PIN_TAG_INVALID 발생.")
        @Test
        void tagNumber_throwsPinTagInvalid() throws Exception {
            // arrange
            PinV1Dto.UpdatePinRequest req = parse("{\"tag\": 1}");

            // act & assert
            assertThatThrownBy(req::toCommand)
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_TAG_INVALID);
        }
    }

    @DisplayName("memo 와 tag 가 모두 제공되었을 때,")
    @Nested
    class BothPresent {

        @DisplayName("둘 다 갱신 대상 커맨드가 생성된다 (AC-6).")
        @Test
        void bothProvided_buildsCommand() throws Exception {
            // arrange
            PinV1Dto.UpdatePinRequest req = parse("{\"memo\": \"x\", \"tag\": \"MEMORY\"}");

            // act
            PinUpdateCommand cmd = req.toCommand();

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo("x");
            assertThat(cmd.tagProvided()).isTrue();
            assertThat(cmd.tag()).isEqualTo(PinTag.MEMORY);
        }
    }
}
