package com.wherewego.domain.user;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface UserRepository {
    /**
     * 활성(deleted_at IS NULL) Kakao 사용자만 조회한다.
     * P2 FR-24: soft-delete 행은 제외 → 삭제 계정 재로그인 시 미스→신규 생성(재가입)으로 이어진다.
     */
    Optional<UserModel> findByKakaoUserIdAndDeletedAtIsNull(Long kakaoUserId);
    /**
     * P1: 네이티브 로그인 find-or-create 용 (provider, oauthId) 조회.
     * P2 FR-24: 활성(deleted_at IS NULL) 행만 조회 → soft-delete 행은 재가입 신규 생성 경로로 제외.
     */
    Optional<UserModel> findByOauthProviderAndOauthIdAndDeletedAtIsNull(OauthProvider oauthProvider, String oauthId);
    Optional<UserModel> findById(Long id);
    UserModel save(UserModel user);
    /** 즉시 flush — 동시 삽입 race 시 DataIntegrityViolationException 조기 감지용. */
    UserModel saveAndFlush(UserModel user);

    /**
     * 주어진 user id 집합에 대한 nickname 맵을 조회한다.
     * 핀 목록 응답에 작성자 닉네임을 N+1 없이 매핑하기 위함.
     */
    Map<Long, String> findNicknamesByIds(Collection<Long> ids);

    /**
     * 주어진 user id 집합에 대한 프로필(닉네임 + 유효 프사 URL) 맵을 조회한다 (GP-1).
     * <p>채팅 메시지 프레임의 발신자 프사 등에 N+1 없이 배치 매핑하기 위함.
     * {@link UserProfile#profileImageUrl()} 은 유효 프사 URL 규칙(프사 썸네일 키 → 카카오 URL 폴백 → null)을
     * 적용한 값이며, 규칙/URL 조합은 어댑터가 수행한다(S3Properties 의존).</p>
     */
    Map<Long, UserProfile> findProfilesByIds(Collection<Long> ids);

    /**
     * 배치 프로필 조회 결과 (GP-1). {@code profileImageUrl} 은 유효 프사 URL(키 우선→카카오 폴백→null).
     */
    record UserProfile(String nickname, String profileImageUrl) {
    }
}
