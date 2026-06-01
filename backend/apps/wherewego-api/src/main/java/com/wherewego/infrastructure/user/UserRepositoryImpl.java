package com.wherewego.infrastructure.user;

import com.wherewego.domain.user.OauthProvider;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;

    @Override
    public Optional<UserModel> findByKakaoUserId(Long kakaoUserId) {
        return jpaRepository.findByKakaoUserId(kakaoUserId);
    }

    @Override
    public Optional<UserModel> findByOauthProviderAndOauthId(OauthProvider oauthProvider, String oauthId) {
        return jpaRepository.findByOauthProviderAndOauthId(oauthProvider, oauthId);
    }

    @Override
    public Optional<UserModel> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public UserModel save(UserModel user) {
        return jpaRepository.save(user);
    }

    @Override
    public UserModel saveAndFlush(UserModel user) {
        return jpaRepository.saveAndFlush(user);
    }

    @Override
    public Map<Long, String> findNicknamesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        return jpaRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserModel::getId, UserModel::getNickname));
    }
}
