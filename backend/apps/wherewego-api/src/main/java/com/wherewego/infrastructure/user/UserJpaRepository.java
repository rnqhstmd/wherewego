package com.wherewego.infrastructure.user;

import com.wherewego.domain.user.UserModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserModel, Long> {
    Optional<UserModel> findByKakaoUserId(Long kakaoUserId);
}
