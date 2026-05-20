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
        UserModel user = findActiveUserById(userId);
        return UserV1Dto.UserResponse.from(user);
    }

    @Transactional
    public UserV1Dto.UserResponse updateNickname(Long userId, String nickname) {
        UserModel user = findActiveUserById(userId);
        user.updateProfile(nickname, user.getProfileImageUrl());
        // JPA dirty checking 으로 트랜잭션 커밋 시 자동 반영된다.
        return UserV1Dto.UserResponse.from(user);
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
