package com.wherewego.domain.user;

import java.util.Optional;

public interface UserRepository {
    Optional<UserModel> findByKakaoUserId(Long kakaoUserId);
    Optional<UserModel> findById(Long id);
    UserModel save(UserModel user);
}
