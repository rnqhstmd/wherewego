package com.wherewego.domain.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkCodeGeneratorTest {

    @DisplayName("6자리 코드를 생성할 때,")
    @Nested
    class Generate6Digits {

        @DisplayName("반환값은 정확히 6자리이고 0 패딩을 포함한다.")
        @Test
        void generate6Digits_returnsSixCharsWithZeroPadding() {
            // arrange
            LinkCodeGenerator generator = new LinkCodeGenerator();

            // act
            String code = generator.generate6Digits();

            // assert
            assertThat(code).hasSize(6);
            assertThat(code).matches("\\d{6}");
        }

        @DisplayName("100회 호출해도 모두 0~999999 범위와 6자리 길이를 유지한다 (BR-1).")
        @Test
        void generate6Digits_repeatedCalls_stayInRange() {
            // arrange
            LinkCodeGenerator generator = new LinkCodeGenerator();

            // act & assert
            for (int i = 0; i < 100; i++) {
                String code = generator.generate6Digits();
                assertThat(code).hasSize(6);
                assertThat(code).matches("\\d{6}");
                int value = Integer.parseInt(code);
                assertThat(value).isBetween(0, 999_999);
            }
        }
    }
}
