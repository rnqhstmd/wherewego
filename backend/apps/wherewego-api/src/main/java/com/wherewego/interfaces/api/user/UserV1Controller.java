package com.wherewego.interfaces.api.user;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.user.UserDeletionService;
import com.wherewego.domain.user.UserService;
import com.wherewego.interfaces.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserV1Controller implements UserV1ApiSpec {

    private final UserService userService;
    private final UserDeletionService userDeletionService;

    @GetMapping("/me")
    @Override
    public ApiResponse<UserV1Dto.UserResponse> getCurrentUser(@AuthUser Long userId) {
        return ApiResponse.success(userService.getCurrentUser(userId));
    }

    @PutMapping("/me")
    @Override
    public ApiResponse<UserV1Dto.UserResponse> updateNickname(
            @AuthUser Long userId,
            @Valid @RequestBody UserV1Dto.UpdateNicknameRequest request
    ) {
        return ApiResponse.success(userService.updateNickname(userId, request.nickname()));
    }

    @DeleteMapping("/me")
    @Override
    public ApiResponse<Object> deleteMe(@AuthUser Long userId) {
        userDeletionService.deleteAccount(userId);
        return ApiResponse.success();
    }
}
