package com.wherewego.domain.pin;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PinUpdateCommand#of} 정적 팩토리의 입력 검증 단위 테스트 (Phase 4 §B3, Phase 2.8 확장).
 *
 * <p>memo / tag / placeName / address 가 각각 독립적으로 제공될 수 있으며,
 * 모두 미제공 / tag 누락 / memo 초과 길이 / placeName blank·초과 / address 초과 시
 * {@link CoreException} 으로 차단된다.</p>
 */
class PinUpdateCommandTest {

    @DisplayName("memo / tag / placeName / address 가 모두 미제공일 때,")
    @Nested
    class EmptyUpdate {

        @DisplayName("PIN_UPDATE_EMPTY 가 발생한다 (AC-9, BR-7).")
        @Test
        void noneProvided_throwsPinUpdateEmpty() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_UPDATE_EMPTY);
        }
    }

    @DisplayName("tag 가 제공되었지만 값이 null 일 때,")
    @Nested
    class TagValidation {

        @DisplayName("PIN_TAG_INVALID 가 발생한다 (AC-13).")
        @Test
        void tagProvidedButNull_throwsPinTagInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, true, null,
                    false, null, false, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_TAG_INVALID);
        }
    }

    @DisplayName("memo 길이 검증을 할 때,")
    @Nested
    class MemoLengthValidation {

        @DisplayName("500 자는 정상 통과한다 (AC-12).")
        @Test
        void memo500Chars_passes() {
            // arrange
            String memo = "a".repeat(500);

            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(true, memo, false, null,
                    false, null, false, null);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo(memo);
            assertThat(cmd.memo()).hasSize(500);
        }

        @DisplayName("501 자는 PIN_MEMO_TOO_LONG 으로 거부된다 (AC-12, BR-4).")
        @Test
        void memo501Chars_throwsPinMemoTooLong() {
            // arrange
            String memo = "a".repeat(501);

            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(true, memo, false, null,
                    false, null, false, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_MEMO_TOO_LONG);
        }

        @DisplayName("빈 문자열은 정상 통과한다 (잠금 해제용, BR-8).")
        @Test
        void memoEmptyString_passes() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(true, "", false, null,
                    false, null, false, null);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEmpty();
        }

        @DisplayName("memoProvided=true 이고 memo=null 이어도 길이 검증을 통과한다.")
        @Test
        void memoProvidedNull_passes() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(true, null, false, null,
                    false, null, false, null);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isNull();
        }
    }

    @DisplayName("placeName 검증을 할 때 (Phase 2.8),")
    @Nested
    class PlaceNameValidation {

        @DisplayName("placeNameProvided=true 이고 null 이면 PIN_PLACE_NAME_INVALID 가 발생한다.")
        @Test
        void placeNameProvidedNull_throwsPinPlaceNameInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    true, null, false, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_PLACE_NAME_INVALID);
        }

        @DisplayName("placeName 이 blank 이면 PIN_PLACE_NAME_INVALID 가 발생한다.")
        @Test
        void placeNameBlank_throwsPinPlaceNameInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    true, "   ", false, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_PLACE_NAME_INVALID);
        }

        @DisplayName("placeName 이 201 자면 PIN_PLACE_NAME_INVALID 가 발생한다.")
        @Test
        void placeName201Chars_throwsPinPlaceNameInvalid() {
            // arrange
            String tooLong = "a".repeat(201);

            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    true, tooLong, false, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_PLACE_NAME_INVALID);
        }

        @DisplayName("placeName 이 1 자 ('x') 이면 정상 통과한다.")
        @Test
        void placeNameOneChar_passes() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                    true, "x", false, null);

            // assert
            assertThat(cmd.placeNameProvided()).isTrue();
            assertThat(cmd.placeName()).isEqualTo("x");
        }
    }

    @DisplayName("address 검증을 할 때 (Phase 2.8),")
    @Nested
    class AddressValidation {

        @DisplayName("addressProvided=true 이고 501 자면 PIN_ADDRESS_INVALID 가 발생한다.")
        @Test
        void address501Chars_throwsPinAddressInvalid() {
            // arrange
            String tooLong = "a".repeat(501);

            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, true, tooLong))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_ADDRESS_INVALID);
        }

        @DisplayName("addressProvided=true 이고 address=null 이어도 정상 통과한다.")
        @Test
        void addressProvidedNull_passes() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                    false, null, true, null);

            // assert
            assertThat(cmd.addressProvided()).isTrue();
            assertThat(cmd.address()).isNull();
        }
    }

    @DisplayName("정상 케이스에서,")
    @Nested
    class NormalCases {

        @DisplayName("memo 만 제공되면 memo 만 갱신 대상이 된다 (AC-7).")
        @Test
        void memoOnly_buildsCommand() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(true, "hello", false, null,
                    false, null, false, null);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo("hello");
            assertThat(cmd.tagProvided()).isFalse();
            assertThat(cmd.tag()).isNull();
        }

        @DisplayName("tag 만 제공되면 tag 만 갱신 대상이 된다 (AC-8).")
        @Test
        void tagOnly_buildsCommand() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(false, null, true, PinTag.MEMORY,
                    false, null, false, null);

            // assert
            assertThat(cmd.memoProvided()).isFalse();
            assertThat(cmd.memo()).isNull();
            assertThat(cmd.tagProvided()).isTrue();
            assertThat(cmd.tag()).isEqualTo(PinTag.MEMORY);
        }

        @DisplayName("memo 와 tag 가 모두 제공되면 둘 다 갱신 대상이 된다 (AC-6).")
        @Test
        void bothProvided_buildsCommand() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(true, "memo", true, PinTag.PLACE,
                    false, null, false, null);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo("memo");
            assertThat(cmd.tagProvided()).isTrue();
            assertThat(cmd.tag()).isEqualTo(PinTag.PLACE);
        }

        @DisplayName("placeName 만 제공되면 placeName 만 갱신 대상이 된다 (Phase 2.8 AC-6).")
        @Test
        void placeNameOnly_buildsCommand() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                    true, "새 장소", false, null);

            // assert
            assertThat(cmd.placeNameProvided()).isTrue();
            assertThat(cmd.placeName()).isEqualTo("새 장소");
            assertThat(cmd.addressProvided()).isFalse();
            assertThat(cmd.address()).isNull();
        }

        @DisplayName("memo 와 placeName 이 함께 제공되면 둘 다 갱신 대상이 된다 (Phase 2.8 동시 수정).")
        @Test
        void memoAndPlaceName_buildsCommand() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(true, "memo", false, null,
                    true, "장소", false, null);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo("memo");
            assertThat(cmd.placeNameProvided()).isTrue();
            assertThat(cmd.placeName()).isEqualTo("장소");
        }
    }
}
