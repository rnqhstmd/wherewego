package com.wherewego.infrastructure.user;

import com.wherewego.config.env.S3Properties;
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
    private final S3Properties s3Properties;

    @Override
    public Optional<UserModel> findByKakaoUserIdAndDeletedAtIsNull(Long kakaoUserId) {
        return jpaRepository.findByKakaoUserIdAndDeletedAtIsNull(kakaoUserId);
    }

    @Override
    public Optional<UserModel> findByOauthProviderAndOauthIdAndDeletedAtIsNull(OauthProvider oauthProvider, String oauthId) {
        return jpaRepository.findByOauthProviderAndOauthIdAndDeletedAtIsNull(oauthProvider, oauthId);
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

    @Override
    public Map<Long, UserProfile> findProfilesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        return jpaRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(
                        UserModel::getId,
                        u -> new UserProfile(
                                u.getNickname(),
                                effectiveProfileImageUrl(u.getProfileImageThumbKey(), u.getProfileImageUrl()))));
    }

    /**
     * 유효 프사 URL 규칙(GP-1): 프사 썸네일 키가 있으면 그 공개 URL, 없으면 카카오 profileImageUrl 폴백,
     * 둘 다 없으면 null. GroupMemberService.effectiveProfileImageUrl 과 동일 규칙.
     */
    private String effectiveProfileImageUrl(String thumbKey, String kakaoUrl) {
        if (thumbKey != null && !thumbKey.isBlank()) {
            return toPublicUrl(thumbKey);
        }
        return kakaoUrl;
    }

    /** S3 객체 키 → 공개 URL (PinService.toPublicUrl 동일). 끝 슬래시 중복 방지. */
    private String toPublicUrl(String key) {
        if (key == null) return null;
        String base = s3Properties.publicBaseUrl().replaceAll("/+$", "");
        return base + "/" + key;
    }
}
