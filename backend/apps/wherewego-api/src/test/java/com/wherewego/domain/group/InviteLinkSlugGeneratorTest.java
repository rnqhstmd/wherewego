package com.wherewego.domain.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InviteLinkSlugGeneratorTest {

    private final InviteLinkSlugGenerator generator = new InviteLinkSlugGenerator();

    @DisplayName("8자 base56 슬러그를 생성한다.")
    @Test
    void generate_lengthAndAlphabet() {
        for (int i = 0; i < 100; i++) {
            String slug = generator.generate();
            assertThat(slug).hasSize(8);
            assertThat(slug).matches("[" + InviteLinkSlugGenerator.ALPHABET + "]+");
        }
    }

    @DisplayName("혼동 문자(0/1/I/O/l/o)를 포함하지 않는다.")
    @Test
    void generate_excludesConfusableChars() {
        for (int i = 0; i < 200; i++) {
            String slug = generator.generate();
            for (char c : new char[]{'0', '1', 'I', 'O', 'l', 'o'}) {
                assertThat(slug).doesNotContain(String.valueOf(c));
            }
        }
    }

    @DisplayName("연속 100회 생성 결과가 서로 다르다 (충돌 검색공간 충분).")
    @Test
    void generate_highUniqueness() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(100);
    }
}
