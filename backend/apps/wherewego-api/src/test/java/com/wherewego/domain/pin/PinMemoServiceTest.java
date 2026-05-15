package com.wherewego.domain.pin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PinMemoServiceTest {

    @Mock
    private PinRepository pinRepository;

    @InjectMocks
    private PinMemoService pinMemoService;

    @DisplayName("2초 윈도우 내 자동 메모를 부착할 때,")
    @Nested
    class AttachAutoMemoIfWithinWindow {

        @DisplayName("핀이 존재하고 MANUAL 메모가 없으면, 1행이 갱신되어 true 를 반환한다 (AC-14).")
        @Test
        void attachAutoMemoIfWithinWindow_pinExistsNoManual_updates() {
            // arrange
            when(pinRepository.updateAutoMemoIfNotManual(42L, 7L, "맛있어요")).thenReturn(1);

            // act
            boolean result = pinMemoService.attachAutoMemoIfWithinWindow(42L, 7L, "맛있어요");

            // assert
            assertThat(result).isTrue();
        }

        @DisplayName("이미 MANUAL 메모가 있으면, 0행이 반환되어 false 를 반환한다 (AC-15).")
        @Test
        void attachAutoMemoIfWithinWindow_pinHasManual_skip() {
            // arrange
            when(pinRepository.updateAutoMemoIfNotManual(42L, 7L, "맛있어요")).thenReturn(0);

            // act
            boolean result = pinMemoService.attachAutoMemoIfWithinWindow(42L, 7L, "맛있어요");

            // assert
            assertThat(result).isFalse();
        }

        @DisplayName("핀이 존재하지 않으면, 0행이 반환되어 false 를 반환한다.")
        @Test
        void attachAutoMemoIfWithinWindow_pinNotFound_returnsFalse() {
            // arrange
            when(pinRepository.updateAutoMemoIfNotManual(99L, 7L, "맛있어요")).thenReturn(0);

            // act
            boolean result = pinMemoService.attachAutoMemoIfWithinWindow(99L, 7L, "맛있어요");

            // assert
            assertThat(result).isFalse();
        }
    }
}
