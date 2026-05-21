package com.wherewego.domain.pin;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PinUpdateCommand#of} 정적 팩토리의 입력 검증 단위 테스트 (Phase 4 §B3, Phase 2.8 확장).
 *
 * <p>memo / tag / placeName / address 가 각각 독립적으로 제공될 수 있으며,
 * 모두 미제공 / tag 누락 / memo 초과 길이 / placeName blank·초과 / address 초과 시
 * {@link CoreException} 으로 차단된다.</p>
 *
 * <p>Phase 2.10: 좌표 수정 케이스(`CoordinateValidation`) 추가. 기존 8 인자 호출들은
 * `coordinateProvided=false, latitude=null, longitude=null` 로 확장된 11 인자 시그니처에 맞춰 패치한다.</p>
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
                    false, null, false, null, false, null, null))
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
                    false, null, false, null, false, null, null))
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
                    false, null, false, null, false, null, null);

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
                    false, null, false, null, false, null, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_MEMO_TOO_LONG);
        }

        @DisplayName("빈 문자열은 정상 통과한다 (잠금 해제용, BR-8).")
        @Test
        void memoEmptyString_passes() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(true, "", false, null,
                    false, null, false, null, false, null, null);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEmpty();
        }

        @DisplayName("memoProvided=true 이고 memo=null 이어도 길이 검증을 통과한다.")
        @Test
        void memoProvidedNull_passes() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(true, null, false, null,
                    false, null, false, null, false, null, null);

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
                    true, null, false, null, false, null, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_PLACE_NAME_INVALID);
        }

        @DisplayName("placeName 이 blank 이면 PIN_PLACE_NAME_INVALID 가 발생한다.")
        @Test
        void placeNameBlank_throwsPinPlaceNameInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    true, "   ", false, null, false, null, null))
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
                    true, tooLong, false, null, false, null, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_PLACE_NAME_INVALID);
        }

        @DisplayName("placeName 이 1 자 ('x') 이면 정상 통과한다.")
        @Test
        void placeNameOneChar_passes() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                    true, "x", false, null, false, null, null);

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
                    false, null, true, tooLong, false, null, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_ADDRESS_INVALID);
        }

        @DisplayName("addressProvided=true + address=null 단독은 미변경으로 정규화되어 PIN_UPDATE_EMPTY 가 발생한다.")
        @Test
        void addressProvidedNullOnly_throwsPinUpdateEmpty() {
            // address 만 provided=true + null 인 경우 정규화 후 모든 필드 미제공이 되어
            // PIN_UPDATE_EMPTY 가 발생한다 (Q5 정책: 빈/null address 는 미변경).
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, true, null, false, null, null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_UPDATE_EMPTY);
        }

        @DisplayName("addressProvided=true + address=null 은 다른 필드와 함께 와도 not-provided 로 정규화된다.")
        @Test
        void addressProvidedNullWithOther_normalizedToNotProvided() {
            // act - memo 만 실제 변경이고 address 는 null → addressProvided 가 false 로 정규화되어야 한다.
            PinUpdateCommand cmd = PinUpdateCommand.of(true, "hello", false, null,
                    false, null, true, null, false, null, null);

            // assert
            assertThat(cmd.addressProvided()).isFalse();
            assertThat(cmd.address()).isNull();
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo("hello");
        }
    }

    @DisplayName("좌표(coordinate) 검증을 할 때 (Phase 2.10),")
    @Nested
    class CoordinateValidation {

        @DisplayName("coordinateProvided=true 이고 latitude=null 이면 PIN_COORDINATE_INVALID 가 발생한다.")
        @Test
        void coordinateProvidedWithLatNull_throwsPinCoordinateInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null, true, null, BigDecimal.valueOf(127.0)))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_COORDINATE_INVALID);
        }

        @DisplayName("coordinateProvided=true 이고 longitude=null 이면 PIN_COORDINATE_INVALID 가 발생한다.")
        @Test
        void coordinateProvidedWithLngNull_throwsPinCoordinateInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null, true, BigDecimal.valueOf(37.5), null))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_COORDINATE_INVALID);
        }

        @DisplayName("latitude 가 91 이면 PIN_COORDINATE_INVALID 가 발생한다.")
        @Test
        void latitudeAbove90_throwsPinCoordinateInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null,
                    true, BigDecimal.valueOf(91), BigDecimal.valueOf(127.0)))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_COORDINATE_INVALID);
        }

        @DisplayName("latitude 가 -91 이면 PIN_COORDINATE_INVALID 가 발생한다.")
        @Test
        void latitudeBelowMinus90_throwsPinCoordinateInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null,
                    true, BigDecimal.valueOf(-91), BigDecimal.valueOf(127.0)))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_COORDINATE_INVALID);
        }

        @DisplayName("longitude 가 181 이면 PIN_COORDINATE_INVALID 가 발생한다.")
        @Test
        void longitudeAbove180_throwsPinCoordinateInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null,
                    true, BigDecimal.valueOf(37.5), BigDecimal.valueOf(181)))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_COORDINATE_INVALID);
        }

        @DisplayName("longitude 가 -181 이면 PIN_COORDINATE_INVALID 가 발생한다.")
        @Test
        void longitudeBelowMinus180_throwsPinCoordinateInvalid() {
            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null,
                    true, BigDecimal.valueOf(37.5), BigDecimal.valueOf(-181)))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_COORDINATE_INVALID);
        }

        @DisplayName("범위 내 좌표 (lat=37.5665, lng=126.9780) 는 정상 통과한다.")
        @Test
        void coordinateProvidedInRange_passes() {
            // arrange
            BigDecimal lat = new BigDecimal("37.5665");
            BigDecimal lng = new BigDecimal("126.9780");

            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null, true, lat, lng);

            // assert
            assertThat(cmd.coordinateProvided()).isTrue();
            assertThat(cmd.latitude()).isEqualTo(lat);
            assertThat(cmd.longitude()).isEqualTo(lng);
        }

        @DisplayName("좌표 + memo 동시 변경 시 두 플래그 모두 true 로 설정된다.")
        @Test
        void coordinateAndMemoTogether_buildsCommand() {
            // arrange
            BigDecimal lat = new BigDecimal("37.5665");
            BigDecimal lng = new BigDecimal("126.9780");

            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(true, "memo", false, null,
                    false, null, false, null, true, lat, lng);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo("memo");
            assertThat(cmd.coordinateProvided()).isTrue();
            assertThat(cmd.latitude()).isEqualTo(lat);
            assertThat(cmd.longitude()).isEqualTo(lng);
        }

        @DisplayName("latitude scale 이 8 (37.12345678) 이면 PIN_COORDINATE_INVALID 가 발생한다 (PRD 좌표 정밀도 7자리 제한).")
        @Test
        void coordinatePrecisionExceeded_throwsPinCoordinateInvalid() {
            // arrange - latitude 가 scale=8 (소수점 8자리) 인 경우
            BigDecimal lat = new BigDecimal("37.12345678");
            BigDecimal lng = new BigDecimal("127.0");

            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null, true, lat, lng))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_COORDINATE_INVALID);
        }

        @DisplayName("longitude scale 이 8 (127.12345678) 이면 PIN_COORDINATE_INVALID 가 발생한다 (PRD 좌표 정밀도 7자리 제한).")
        @Test
        void coordinateLongitudePrecisionExceeded_throwsPinCoordinateInvalid() {
            // arrange - longitude 가 scale=8 (소수점 8자리) 인 경우
            BigDecimal lat = new BigDecimal("37.5");
            BigDecimal lng = new BigDecimal("127.12345678");

            // act & assert
            assertThatThrownBy(() -> PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null, true, lat, lng))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_COORDINATE_INVALID);
        }

        @DisplayName("좌표 scale 이 정확히 7 (37.1234567 / 127.1234567) 이면 경계값으로 정상 통과한다.")
        @Test
        void coordinatePrecisionAtBoundary_passes() {
            // arrange - scale=7 (경계값, 허용)
            BigDecimal lat = new BigDecimal("37.1234567");
            BigDecimal lng = new BigDecimal("127.1234567");

            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null, true, lat, lng);

            // assert
            assertThat(cmd.coordinateProvided()).isTrue();
            assertThat(cmd.latitude()).isEqualTo(lat);
            assertThat(cmd.longitude()).isEqualTo(lng);
        }

        @DisplayName("trailing 0 이 있는 좌표 (37.50000000) 는 stripTrailingZeros 후 scale=0 이므로 정상 통과한다.")
        @Test
        void coordinateTrailingZeros_passes() {
            // arrange - scale=8 이지만 의미상으로는 37.5 (stripTrailingZeros 후 scale=1)
            BigDecimal lat = new BigDecimal("37.50000000");
            BigDecimal lng = new BigDecimal("127.00000000");

            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                    false, null, false, null, true, lat, lng);

            // assert
            assertThat(cmd.coordinateProvided()).isTrue();
            assertThat(cmd.latitude()).isEqualTo(lat);
            assertThat(cmd.longitude()).isEqualTo(lng);
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
                    false, null, false, null, false, null, null);

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
                    false, null, false, null, false, null, null);

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
            PinUpdateCommand cmd = PinUpdateCommand.of(true, "memo", true, PinTag.WISH,
                    false, null, false, null, false, null, null);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo("memo");
            assertThat(cmd.tagProvided()).isTrue();
            assertThat(cmd.tag()).isEqualTo(PinTag.WISH);
        }

        @DisplayName("placeName 만 제공되면 placeName 만 갱신 대상이 된다 (Phase 2.8 AC-6).")
        @Test
        void placeNameOnly_buildsCommand() {
            // act
            PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                    true, "새 장소", false, null, false, null, null);

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
                    true, "장소", false, null, false, null, null);

            // assert
            assertThat(cmd.memoProvided()).isTrue();
            assertThat(cmd.memo()).isEqualTo("memo");
            assertThat(cmd.placeNameProvided()).isTrue();
            assertThat(cmd.placeName()).isEqualTo("장소");
        }
    }
}
