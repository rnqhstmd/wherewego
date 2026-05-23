package com.wherewego.domain.user;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface UserRepository {
    Optional<UserModel> findByKakaoUserId(Long kakaoUserId);
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
