package com.wherewego.domain.user;

import com.wherewego.interfaces.api.user.UserV1Dto;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserV1Dto.UserResponse getCurrentUser(Long userId) {
        UserModel user = userRepository.findById(userId)
                .orElseThrow(() -> new CoreException(ErrorType.AUTH_USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED);
        }
        return UserV1Dto.UserResponse.from(user);
    }

    @Transactional
    public UserV1Dto.UserResponse updateNickname(Long userId, String nickname) {
        UserModel user = userRepository.findById(userId)
                .orElseThrow(() -> new CoreException(ErrorType.AUTH_USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new CoreException(ErrorType.AUTH_USER_DEACTIVATED);
        }
        user.updateProfile(nickname, user.getProfileImageUrl());
        UserModel saved = userRepository.save(user);
        return UserV1Dto.UserResponse.from(saved);
    }
}
