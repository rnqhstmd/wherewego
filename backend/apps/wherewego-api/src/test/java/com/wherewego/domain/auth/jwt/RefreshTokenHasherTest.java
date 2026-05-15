package com.wherewego.domain.auth.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenHasherTest {

    private final RefreshTokenHasher hasher = new RefreshTokenHasher();

    @DisplayName("SHA-256 해시를 계산할 때,")
    @Nested
    class Sha256Hex {
        @DisplayName("같은 입력을 두 번 해시하면, 동일한 hex 결과를 반환한다.")
        @Test
        void sha256Hex_sameInput_sameHash() {
            // arrange
            String token = "some-refresh-token";

            // act
            String first = hasher.sha256Hex(token);
            String second = hasher.sha256Hex(token);

            // assert
            assertThat(first).isEqualTo(second);
        }

        @DisplayName("서로 다른 입력을 해시하면, 다른 hex 결과를 반환한다.")
        @Test
        void sha256Hex_differentInput_differentHash() {
            // act
            String h1 = hasher.sha256Hex("token-A");
            String h2 = hasher.sha256Hex("token-B");

            // assert
            assertThat(h1).isNotEqualTo(h2);
        }

        @DisplayName("결과는 64자 lowercase hex 문자열이다.")
        @Test
        void sha256Hex_returns64CharLowerHex() {
            // act
            String hash = hasher.sha256Hex("any-token");

            // assert
            assertThat(hash).hasSize(64);
            assertThat(hash).matches("^[0-9a-f]{64}$");
        }
    }
}
