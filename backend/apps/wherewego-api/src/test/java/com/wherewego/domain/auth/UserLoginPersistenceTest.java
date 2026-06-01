package com.wherewego.domain.auth;

import com.wherewego.application.auth.AuthResultInfo;
import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserLoginPersistenceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenHasher refreshTokenHasher;

    @InjectMocks
    private UserLoginPersistence persistence;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.issueAccessToken(anyLong())).thenReturn("access-token");
        when(jwtTokenProvider.issueRefreshToken(anyLong())).thenReturn("refresh-token");
        when(refreshTokenHasher.sha256Hex(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0));
    }

    @DisplayName("upsertAndIssueTokens를 호출할 때,")
    @Nested
    class UpsertAndIssueTokens {

        @DisplayName("신규 사용자면, 저장 후 토큰을 발급한다.")
        @Test
        void newUser_savesAndIssuesTokens() {
            // arrange
            UserModel saved = spy(UserModel.create(12345L, "닉네임", "img.png"));
            when(userRepository.findByKakaoUserIdAndDeletedAtIsNull(12345L)).thenReturn(Optional.empty());
            when(userRepository.saveAndFlush(any())).thenReturn(saved);
            when(userRepository.save(any())).thenReturn(saved);

            // act
            AuthResultInfo result = persistence.upsertAndIssueTokens(12345L, "닉네임", "img.png");

            // assert
            verify(userRepository).saveAndFlush(any(UserModel.class));
            assertThat(result.accessToken()).isEqualTo("access-token");
            assertThat(result.refreshToken()).isEqualTo("refresh-token");
            assertThat(result.nickname()).isEqualTo("닉네임");
        }

        @DisplayName("기존 활성 사용자면, 프로필을 갱신하고 토큰을 발급한다.")
        @Test
        void existingActiveUser_updatesProfileAndIssuesTokens() {
            // arrange
            UserModel existing = spy(UserModel.create(12345L, "OldName", "old.png"));
            when(userRepository.findByKakaoUserIdAndDeletedAtIsNull(12345L)).thenReturn(Optional.of(existing));
            when(userRepository.save(any())).thenReturn(existing);

            // act
            persistence.upsertAndIssueTokens(12345L, "NewName", "new.png");

            // assert
            verify(existing).updateProfile("NewName", "new.png");
        }

        @DisplayName("활성 조회가 (조회-삭제 race 로) 비활성 행을 반환하면, 방어적 가드가 AUTH_USER_DEACTIVATED 예외를 던진다.")
        @Test
        void activeQueryReturnsInactive_throwsDeactivated() {
            // P2 FR-24: 일반 로그인 경로는 활성 조회(findByKakaoUserIdAndDeletedAtIsNull)로 soft-delete 행을 미스하므로
            // 탈퇴자는 신규 생성(재가입)으로 처리된다. 다만 조회 직후 삭제되는 동시성 race 에 대비해 source 에
            // isActive() 방어 가드가 남아 있다. 이 테스트는 그 방어 분기만 직접 검증한다(활성 조회 stub 이 비활성 행을 반환).
            UserModel existing = UserModel.create(12345L, "닉", null);
            existing.delete();
            when(userRepository.findByKakaoUserIdAndDeletedAtIsNull(12345L)).thenReturn(Optional.of(existing));

            // act & assert
            assertThatThrownBy(() -> persistence.upsertAndIssueTokens(12345L, "닉", null))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.AUTH_USER_DEACTIVATED);
        }

        @DisplayName("동시 최초 로그인으로 unique constraint 위반 시, DataIntegrityViolationException이 전파된다.")
        @Test
        void concurrentFirstLogin_dataIntegrityViolation_propagatesException() {
            // arrange
            // 단위 테스트에서는 @Retryable AOP 프록시가 동작하지 않으므로 예외가 그대로 전파된다.
            // 실제 운영에서는 @Retryable이 잡아 재시도 → findByKakaoUserIdAndDeletedAtIsNull가 기존 사용자를 반환해 정상 처리된다.
            // 재시도 후 정상 처리 경로는 existingActiveUser_updatesProfileAndIssuesTokens 테스트가 커버한다.
            when(userRepository.findByKakaoUserIdAndDeletedAtIsNull(12345L)).thenReturn(Optional.empty());
            when(userRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

            // act & assert
            assertThatThrownBy(() -> persistence.upsertAndIssueTokens(12345L, "닉네임", "img.png"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
