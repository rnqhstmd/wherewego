package com.wherewego.domain.user;

import com.wherewego.domain.BaseEntity;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Duration;
import java.time.ZonedDateTime;

@Entity
@Getter
@Table(name = "users")
public class UserModel extends BaseEntity {

    /**
     * P1: OAuth 공급자(KAKAO/APPLE). 기존 행은 V014 에서 KAKAO 로 백필.
     */
    @Column(name = "oauth_provider", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private OauthProvider oauthProvider;

    /**
     * P1: 공급자별 식별자. Kakao=kakao_user_id::text, Apple=identityToken sub.
     */
    @Column(name = "oauth_id", nullable = false, length = 255)
    private String oauthId;

    /**
     * P1: Apple 최초 로그인 1회 저장. Kakao 미수집(NULL).
     */
    @Column(name = "email")
    private String email;

    // nullable=false 제거 (Apple 행은 kakao_user_id NULL).
    // UNIQUE 는 V017에서 partial unique index(WHERE deleted_at IS NULL)로 전환 — 활성 행만 유일. 재가입(FR-24) 위해 soft-delete 행은 제외.
    @Column(name = "kakao_user_id")
    private Long kakaoUserId;

    @Column(name = "nickname", nullable = false, length = 100)
    private String nickname;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    /**
     * GP-1: 사용자 프로필 사진 원본 S3 객체 키 (V022). NULL = 업로드 사진 없음(profileImageUrl 폴백).
     * 공개 URL 은 서비스/어댑터에서 S3Properties 와 조합한다.
     */
    @Column(name = "profile_image_key")
    private String profileImageKey;

    /** GP-1: 프로필 사진 썸네일 S3 객체 키 (V022). 유효 프사 URL 의 1순위 소스. 원본과 uuid 공유. */
    @Column(name = "profile_image_thumb_key")
    private String profileImageThumbKey;

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
        // 기존 Kakao 생성 경로 — provider/oauthId 를 내부에서 KAKAO 로 세팅해 호출부는 무변경.
        this.oauthProvider = OauthProvider.KAKAO;
        this.oauthId = kakaoUserId != null ? kakaoUserId.toString() : null;
        this.kakaoUserId = kakaoUserId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        guard();
    }

    private UserModel(OauthProvider oauthProvider, String oauthId, String nickname, String profileImageUrl, String email) {
        this.oauthProvider = oauthProvider;
        this.oauthId = oauthId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.email = email;
        // Kakao 경로는 kakaoUserId 도 채운다 (하위호환 컬럼·UNIQUE 유지).
        if (oauthProvider == OauthProvider.KAKAO && oauthId != null) {
            this.kakaoUserId = Long.valueOf(oauthId);
        }
        guard();
    }

    @Override
    protected void guard() {
        if (oauthProvider == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "oauthProvider는 비어있을 수 없습니다.");
        }
        if (oauthId == null || oauthId.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "oauthId는 비어있을 수 없습니다.");
        }
        if (oauthProvider == OauthProvider.KAKAO && kakaoUserId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "kakaoUserId는 비어있을 수 없습니다.");
        }
        if (nickname == null || nickname.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "nickname은 비어있을 수 없습니다.");
        }
    }

    public static UserModel create(Long kakaoUserId, String nickname, String profileImageUrl) {
        return new UserModel(kakaoUserId, nickname, profileImageUrl);
    }

    /**
     * P1: 공급자 일반화 팩토리. Apple 등 Kakao 외 공급자 계정 생성에 사용한다.
     * email/profileImageUrl 은 공급자에 따라 null 가능.
     */
    public static UserModel createOauth(OauthProvider oauthProvider, String oauthId,
                                        String nickname, String profileImageUrl, String email) {
        return new UserModel(oauthProvider, oauthId, nickname, profileImageUrl, email);
    }

    /**
     * 닉네임/프사 URL 갱신. {@code UserService.updateNickname}(닉네임 변경 API)이 사용한다.
     * <p>GP-1 FR-7: 카카오 재로그인 시 자동 프로필 동기화는 중단됐다(UserLoginPersistence 호출 제거).
     * 본 메서드는 사용자 명시 닉네임 변경에만 남으며, 호출자는 profileImageUrl 에 기존 값을 그대로 전달한다.</p>
     */
    public void updateProfile(String nickname, String profileImageUrl) {
        if (nickname != null && !nickname.isBlank()) {
            this.nickname = nickname;
        }
        this.profileImageUrl = profileImageUrl;
    }

    /**
     * GP-1: 프로필 사진 키 갱신(업로드/교체). 검증/S3 저장은 서비스가 마친 정상 키를 받는다.
     */
    public void updateProfileImage(String key, String thumbKey) {
        this.profileImageKey = key;
        this.profileImageThumbKey = thumbKey;
    }

    /**
     * GP-1: 프로필 사진 제거 — 업로드 키 2개와 함께 카카오 profileImageUrl 도 null 로 비운다.
     * <p>동기화 중단(FR-7)으로 카카오 URL 이 자동 복원되지 않으므로, "제거"는 키·URL 전부를 비워
     * "프사 없음" 상태를 확정한다(이후 새 업로드로만 재설정).</p>
     */
    public void clearProfileImage() {
        this.profileImageKey = null;
        this.profileImageThumbKey = null;
        this.profileImageUrl = null;
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
