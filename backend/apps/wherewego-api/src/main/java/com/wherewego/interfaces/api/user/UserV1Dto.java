package com.wherewego.interfaces.api.user;

import com.wherewego.domain.user.UserModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UserV1Dto {

    public record UpdateNicknameRequest(
            @NotBlank
            @Size(min = 2, max = 12)
            @Pattern(regexp = "^[가-힣a-zA-Z0-9]+$", message = "한글/영문/숫자만 사용 가능합니다.")
            String nickname
    ) { }

    /**
     * 사용자 응답. {@code profileImageUrl} 은 유효 프사 URL 규칙(프사 썸네일 키 → 카카오 URL 폴백 → null, GP-1)을
     * 따른다 — 서비스가 {@code UserRepository.findProfilesByIds} resolver 로 계산해 {@link #from(UserModel, String)}
     * 으로 주입한다(record 는 S3Properties 접근 불가하므로 raw 카카오 URL 직참조를 금지).
     */
    public record UserResponse(Long id, String nickname, String profileImageUrl) {
        /**
         * 유효 프사 URL 을 주입받아 응답을 만든다(GP-1). {@code profileImageUrl} 은 서비스가 산출한 유효 URL 이다.
         */
        public static UserResponse from(UserModel user, String profileImageUrl) {
            return new UserResponse(user.getId(), user.getNickname(), profileImageUrl);
        }
    }

    private UserV1Dto() { }
}
