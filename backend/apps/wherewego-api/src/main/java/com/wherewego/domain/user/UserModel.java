package com.wherewego.domain.user;

import com.wherewego.domain.BaseEntity;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Getter
@Table(name = "users")
public class UserModel extends BaseEntity {

    @Column(name = "kakao_user_id", nullable = false, unique = true)
    private Long kakaoUserId;

    @Column(name = "nickname", nullable = false, length = 100)
    private String nickname;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Column(name = "refresh_token")
    private String refreshTokenHash;

    protected UserModel() {}

    public UserModel(Long kakaoUserId, String nickname, String profileImageUrl) {
        this.kakaoUserId = kakaoUserId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        guard();
    }

    @Override
    protected void guard() {
        if (kakaoUserId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "kakaoUserId는 비어있을 수 없습니다.");
        }
        if (nickname == null || nickname.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "nickname은 비어있을 수 없습니다.");
        }
    }

    public static UserModel create(Long kakaoUserId, String nickname, String profileImageUrl) {
        return new UserModel(kakaoUserId, nickname, profileImageUrl);
    }

    public void updateProfile(String nickname, String profileImageUrl) {
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = nickname;
        }
        this.profileImageUrl = profileImageUrl;
    }

    public void replaceRefreshTokenHash(String hash) {
        this.refreshTokenHash = hash;
    }

    public void clearRefreshTokenHash() {
        this.refreshTokenHash = null;
    }

    public boolean matchesRefreshTokenHash(String hash) {
        return this.refreshTokenHash != null && this.refreshTokenHash.equals(hash);
    }

    public boolean isActive() {
        return getDeletedAt() == null;
    }
}
