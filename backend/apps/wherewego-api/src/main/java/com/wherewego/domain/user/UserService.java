package com.wherewego.domain.user;

import com.wherewego.domain.image.AvatarStorage;
import com.wherewego.domain.image.AvatarStorage.StoredAvatar;
import com.wherewego.domain.user.UserRepository.UserProfile;
import com.wherewego.interfaces.api.user.UserV1Dto;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AvatarStorage avatarStorage;

    @Transactional(readOnly = true)
    public UserV1Dto.UserResponse getCurrentUser(Long userId) {
        UserModel user = findActiveUserById(userId);
        return toResponse(user);
    }

    @Transactional
    public UserV1Dto.UserResponse updateNickname(Long userId, String nickname) {
        UserModel user = findActiveUserById(userId);
        user.updateProfile(nickname, user.getProfileImageUrl());
        // JPA dirty checking 으로 트랜잭션 커밋 시 자동 반영된다.
        return toResponse(user);
    }

    /**
     * 내 프로필 사진 업로드/교체 (GP-1 FR-3/BR-2/BR-3). 권한은 본인(인증 사용자 == 대상).
     * <p>검증된 원본 bytes → S3 저장({@code users/{userId}/avatar}) → 이전 키 백업 → {@code updateProfileImage} →
     * 이전 키가 있었으면 best-effort 회수(교체 시 고아 방지) → 갱신 응답. imageBytes/contentType 은 컨트롤러
     * ({@code ImageUploadGuard})가 타입/크기/매직을 검증한 값이며, 픽셀 상한은 어댑터가 검증한다.</p>
     */
    @Transactional
    public UserV1Dto.UserResponse uploadProfileImage(Long userId, byte[] imageBytes, String contentType) {
        UserModel user = findActiveUserById(userId);

        String oldImageKey = user.getProfileImageKey();
        String oldThumbKey = user.getProfileImageThumbKey();
        boolean hadImage = oldImageKey != null;

        StoredAvatar stored = avatarStorage.store("users/" + userId + "/avatar", imageBytes, contentType);
        user.updateProfileImage(stored.imageKey(), stored.thumbKey());

        if (hadImage) {
            // 교체: 기존 객체 best-effort 회수(실패해도 새 사진은 유효, BR-3). 커밋 후 실행(롤백 시 보존).
            deleteAvatarAfterCommit(oldImageKey, oldThumbKey);
        }
        return toResponse(user);
    }

    /**
     * 내 프로필 사진 제거 (GP-1 FR-3/Q4). 권한은 본인. 업로드 키 2개 + 카카오 profileImageUrl 까지 null 로 비워
     * "프사 없음" 상태를 확정한 뒤(clearProfileImage, FR-7 동기화 중단으로 자동 복원 없음) S3 2객체를
     * best-effort 삭제한다. 업로드 키가 없던 사용자는 S3 호출 없이 카카오 URL 만 비운다.
     */
    @Transactional
    public UserV1Dto.UserResponse deleteProfileImage(Long userId) {
        UserModel user = findActiveUserById(userId);

        String oldImageKey = user.getProfileImageKey();
        String oldThumbKey = user.getProfileImageThumbKey();
        user.clearProfileImage();
        if (oldImageKey != null) {
            // 커밋 후 회수(PR#123 리뷰) — 롤백 시 키가 남으므로 객체도 보존돼야 정합.
            deleteAvatarAfterCommit(oldImageKey, oldThumbKey);
        }
        return toResponse(user);
    }

    /**
     * 이전 아바타 객체를 트랜잭션 커밋 후 best-effort 회수한다(PR#123 리뷰 — DB/S3 정합).
     * 트랜잭션 내에서 즉시 삭제하면 롤백 시 DB 키는 남고 S3 객체만 사라져 깨진 링크가 된다.
     * 활성 트랜잭션이 없으면(테스트 등) 즉시 삭제로 폴백. GroupMemberService 와 동일 패턴.
     */
    private void deleteAvatarAfterCommit(String imageKey, String thumbKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    avatarStorage.deleteQuietly(imageKey, thumbKey);
                }
            });
        } else {
            avatarStorage.deleteQuietly(imageKey, thumbKey);
        }
    }

    /**
     * 유효 프사 URL 규칙(GP-1)을 적용해 응답을 만든다. URL 변환/폴백은 {@code UserRepository.findProfilesByIds}
     * resolver(S3Properties 기반 어댑터)를 재사용한다 — 중복 구현 금지(설계 §1.4).
     */
    private UserV1Dto.UserResponse toResponse(UserModel user) {
        UserProfile profile = userRepository.findProfilesByIds(Set.of(user.getId())).get(user.getId());
        String profileImageUrl = profile != null ? profile.profileImageUrl() : user.getProfileImageUrl();
        return UserV1Dto.UserResponse.from(user, profileImageUrl);
    }

    private UserModel findActiveUserById(Long userId) {
        UserModel user = userRepository.findById(userId)
                .orElseThrow(() -> new CoreException(ErrorType.AUTH_USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED);
        }
        return user;
    }
}
