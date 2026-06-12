package com.wherewego.interfaces.api.user;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.user.UserDeletionService;
import com.wherewego.domain.user.UserService;
import com.wherewego.interfaces.api.ApiResponse;
import com.wherewego.interfaces.api.support.ImageUploadGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @PostMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Override
    public ApiResponse<UserV1Dto.UserResponse> uploadProfileImage(
            @AuthUser Long userId,
            @RequestParam("file") MultipartFile file
    ) {
        // GP-1: 3중 검증(타입/크기/매직바이트)을 ImageUploadGuard 로 위임. 범용 IMAGE_* 에러타입.
        byte[] imageBytes = ImageUploadGuard.readValidatedImage(file);
        return ApiResponse.success(userService.uploadProfileImage(userId, imageBytes, file.getContentType()));
    }

    @DeleteMapping("/me/profile-image")
    @Override
    public ApiResponse<UserV1Dto.UserResponse> deleteProfileImage(@AuthUser Long userId) {
        return ApiResponse.success(userService.deleteProfileImage(userId));
    }
}
