package com.wherewego.infrastructure.user;

import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;

    @Override
    public Optional<UserModel> findByKakaoUserId(Long kakaoUserId) {
        return jpaRepository.findByKakaoUserId(kakaoUserId);
    }

    @Override
    public Optional<UserModel> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public UserModel save(UserModel user) {
        return jpaRepository.save(user);
    }
}
