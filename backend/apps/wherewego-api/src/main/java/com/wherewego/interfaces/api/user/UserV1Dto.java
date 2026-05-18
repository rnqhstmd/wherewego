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

    public record UserResponse(Long id, String nickname, String profileImageUrl) {
        public static UserResponse from(UserModel user) {
            return new UserResponse(user.getId(), user.getNickname(), user.getProfileImageUrl());
        }
    }

    private UserV1Dto() { }
}
