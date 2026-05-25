package com.wherewego.domain.group;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 초대 링크 단축 슬러그 생성기 (Phase 11 PR-A).
 *
 * <p>혼동 문자(0/O/I/l/1 + 소문자 o)를 제외한 base56 알파벳 8자 슬러그.
 * 카톡으로 공유 후 손으로 입력하는 경우의 가독성을 우선한다.</p>
 *
 * <p>검색 공간: 56^8 ≈ 9.7e13 (약 96조). 분당 30회 IP 레이트리밋과 결합하면
 * 무차별 대입은 비현실적이다.</p>
 */
@Component
public class InviteLinkSlugGenerator {

    static final String ALPHABET =
            "23456789"                  // 숫자: 0, 1 제외 → 8자
            + "ABCDEFGHJKLMNPQRSTUVWXYZ" // 대문자: I, O 제외 → 24자
            + "abcdefghijkmnpqrstuvwxyz"; // 소문자: l, o 제외 → 24자

    static final int SLUG_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] buf = new char[SLUG_LENGTH];
        for (int i = 0; i < SLUG_LENGTH; i++) {
            buf[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(buf);
    }
}
