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
}
