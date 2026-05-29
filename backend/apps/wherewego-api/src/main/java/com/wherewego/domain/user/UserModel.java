package com.wherewego.domain.user;

import com.wherewego.domain.BaseEntity;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Duration;
import java.time.ZonedDateTime;

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

    /**
     * Phase 12: 오래된 핀 정리 배너 snooze 만료 시각 (V012).
     * NULL = snooze 없음 (배너 정상 노출). non-null 이면 만료 이전까지 정리 후보 조회 응답은
     * 빈 목록과 함께 본 값을 함께 내려보낸다 (D-11).
     */
    @Column(name = "cleanup_snoozed_until")
    private ZonedDateTime cleanupSnoozedUntil;

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

    /**
     * Phase 12: 오래된 핀 정리 배너를 주어진 기간만큼 snooze 한다.
     * 기존 값은 덮어쓴다 (재snooze 가능). 호출자가 {@link Duration} 으로 단위를 명시한다.
     */
    public void snoozeCleanup(Duration duration) {
        this.cleanupSnoozedUntil = ZonedDateTime.now().plus(duration);
    }

    /**
     * Phase 12: 현재 시각 기준 정리 배너가 snooze 상태인지 여부.
     * {@code cleanupSnoozedUntil} 가 null 이거나 이미 만료(과거)면 false.
     */
    public boolean isCleanupSnoozed(ZonedDateTime now) {
        return cleanupSnoozedUntil != null && cleanupSnoozedUntil.isAfter(now);
    }
}
