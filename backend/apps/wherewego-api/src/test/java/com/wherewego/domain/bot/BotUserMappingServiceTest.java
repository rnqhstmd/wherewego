package com.wherewego.domain.bot;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BotUserMappingServiceTest {

    @Mock
    private BotUserMappingRepository mappingRepository;

    @Mock
    private BotLinkCodeService linkCodeService;

    @InjectMocks
    private BotUserMappingService botUserMappingService;

    @DisplayName("봇 사용자를 연동할 때,")
    @Nested
    class Link {

        @DisplayName("유효한 코드면, consumeCode 호출 후 매핑을 저장한다 (AC-4).")
        @Test
        void link_validCode_savesMapping() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            when(mappingRepository.findByBotUserKey("kakao-user-1")).thenReturn(Optional.empty());
            when(linkCodeService.peekUserId("123456")).thenReturn(7L);
            when(mappingRepository.findByUserId(7L)).thenReturn(Optional.empty());
            when(linkCodeService.consumeCode("123456", now)).thenReturn(new BotLinkCodeConsumeResult(7L, "123456"));
            when(mappingRepository.save(any(BotUserMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            // act
            BotUserLinkResult result = botUserMappingService.link("123456", "kakao-user-1", now);

            // assert
            verify(linkCodeService).consumeCode("123456", now);
            verify(mappingRepository).save(any(BotUserMapping.class));
            assertThat(result.userId()).isEqualTo(7L);
            assertThat(result.botUserKey()).isEqualTo("kakao-user-1");
            assertThat(result.linkedAt()).isEqualTo(now);
        }

        @DisplayName("동일 botUserKey 매핑이 이미 있으면, BOT_USER_ALREADY_LINKED 예외가 발생하고 consumeCode 는 호출되지 않는다 (AC-6).")
        @Test
        void link_botUserKeyAlreadyMapped_throwsAlreadyLinked() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            BotUserMapping existing = BotUserMapping.link("kakao-user-1", 9L, now.minusSeconds(60));
            when(mappingRepository.findByBotUserKey("kakao-user-1")).thenReturn(Optional.of(existing));

            // act & assert
            assertThatThrownBy(() -> botUserMappingService.link("123456", "kakao-user-1", now))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.BOT_USER_ALREADY_LINKED);

            verify(linkCodeService, never()).consumeCode(anyString(), any(Instant.class));
            verify(mappingRepository, never()).save(any(BotUserMapping.class));
        }

        @DisplayName("코드는 유효하나 해당 userId 가 다른 botUserKey 로 이미 매핑되어 있으면, consumeCode 호출 전에 예외가 발생한다 (C5).")
        @Test
        void link_userIdAlreadyMapped_throwsAlreadyLinked() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            BotUserMapping otherMapping = BotUserMapping.link("kakao-user-other", 7L, now.minusSeconds(120));
            when(mappingRepository.findByBotUserKey("kakao-user-1")).thenReturn(Optional.empty());
            when(linkCodeService.peekUserId("123456")).thenReturn(7L);
            when(mappingRepository.findByUserId(7L)).thenReturn(Optional.of(otherMapping));

            // act & assert
            assertThatThrownBy(() -> botUserMappingService.link("123456", "kakao-user-1", now))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.BOT_USER_ALREADY_LINKED);

            verify(linkCodeService, never()).consumeCode(anyString(), any(Instant.class));
            verify(mappingRepository, never()).save(any(BotUserMapping.class));
        }

        @DisplayName("peekUserId 가 BOT_LINK_CODE_INVALID 를 던지면, 그대로 전파된다.")
        @Test
        void link_codeInvalid_throwsInvalid() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            when(mappingRepository.findByBotUserKey("kakao-user-1")).thenReturn(Optional.empty());
            when(linkCodeService.peekUserId("999999"))
                    .thenThrow(new CoreException(ErrorType.BOT_LINK_CODE_INVALID));

            // act & assert
            assertThatThrownBy(() -> botUserMappingService.link("999999", "kakao-user-1", now))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.BOT_LINK_CODE_INVALID);
        }
    }

    @DisplayName("botUserKey 로 userId 를 조회할 때,")
    @Nested
    class ResolveUserId {

        @DisplayName("매핑이 존재하면, userId 를 반환한다.")
        @Test
        void resolveUserId_existing_returnsUserId() {
            // arrange
            BotUserMapping mapping = BotUserMapping.link("kakao-user-1", 7L, Instant.parse("2026-01-01T00:00:00Z"));
            when(mappingRepository.findByBotUserKey("kakao-user-1")).thenReturn(Optional.of(mapping));

            // act
            Optional<Long> resolved = botUserMappingService.resolveUserId("kakao-user-1");

            // assert
            assertThat(resolved).contains(7L);
        }
    }
}
