package com.wherewego.domain.pin;

import com.wherewego.domain.place.PlaceSearchHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>설계서에는 {@code replaceAutoMemo(memo)} / {@code hasManualMemo()} 메서드 검증이 포함되어 있으나,
 * 현 {@link Pin} 엔티티에는 해당 메서드가 존재하지 않는다. (Phase 2 자기점검 수정에서 race-safe
 * 조건부 UPDATE 로 통합되면서 도메인 메서드 대신 {@code PinRepository.updateAutoMemoIfNotManual}
 * 로 구현됨.) 본 단위 테스트는 실제로 존재하는 정적 팩토리/필드 초기 상태만 검증한다.</p>
 */
class PinTest {

    private static final PlaceSearchHit HIT = new PlaceSearchHit(
            "kakao-place-1",
            "스타벅스 강남R점",
            "서울 강남구 강남대로 390",
            37.4979,
            127.0276
    );

    @DisplayName("인스타그램 자동 등록 핀을 만들 때,")
    @Nested
    class AutoFromInstagram {

        @DisplayName("tag=PLACE 이고 memoSource=null 로 생성된다 (AC-9, BR-5).")
        @Test
        void autoFromInstagram_setsPlaceTagAndNullMemoSource() {
            // act
            Pin pin = Pin.autoFromInstagram(10L, 7L, HIT, "https://www.instagram.com/p/ABC/");

            // assert
            assertThat(pin.getTag()).isEqualTo(PinTag.PLACE);
            assertThat(pin.getMemoSource()).isNull();
            assertThat(pin.getGroupId()).isEqualTo(10L);
            assertThat(pin.getCreatedBy()).isEqualTo(7L);
            assertThat(pin.getPlaceName()).isEqualTo("스타벅스 강남R점");
            assertThat(pin.getAddress()).isEqualTo("서울 강남구 강남대로 390");
            assertThat(pin.getLatitude()).isEqualByComparingTo(BigDecimal.valueOf(37.4979));
            assertThat(pin.getLongitude()).isEqualByComparingTo(BigDecimal.valueOf(127.0276));
            assertThat(pin.getInstagramUrl()).isEqualTo("https://www.instagram.com/p/ABC/");
            assertThat(pin.getMemo()).isNull();
        }
    }

    @DisplayName("사용자가 후보 카드에서 선택한 핀을 만들 때,")
    @Nested
    class FromSelection {

        @DisplayName("tag=PLACE 이고 memoSource=null 로 생성된다 (BR-5).")
        @Test
        void fromSelection_setsPlaceTagAndNullMemoSource() {
            // act
            Pin pin = Pin.fromSelection(10L, 7L, HIT, "https://www.instagram.com/p/ABC/");

            // assert
            assertThat(pin.getTag()).isEqualTo(PinTag.PLACE);
            assertThat(pin.getMemoSource()).isNull();
            assertThat(pin.getMemo()).isNull();
        }
    }

    @DisplayName("핀 생성 직후 메모 상태를 확인할 때,")
    @Nested
    class InitialMemoState {

        @DisplayName("memoSource 는 null 이며 MANUAL 이 아니므로 자동 메모 부착이 허용되는 상태이다 (BR-11).")
        @Test
        void initial_memoSource_isNotManual() {
            // arrange
            Pin pin = Pin.autoFromInstagram(10L, 7L, HIT, "https://www.instagram.com/p/ABC/");

            // assert - memoSource == MANUAL 이 아닐 때만 자동 메모 부착이 허용된다
            assertThat(pin.getMemoSource()).isNotEqualTo(MemoSource.MANUAL);
        }
    }
}
