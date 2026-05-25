package com.wherewego.domain.group;

import java.time.Instant;

/**
 * 초대 링크 미리보기 결과. 공개 GET by-slug 응답용.
 * token 은 로그인 후 기존 accept API 호출에 사용된다.
 */
public record InviteLinkPreviewResult(
        String token,
        String groupName,
        String inviterNickname,
        Instant expiresAt
) { }
