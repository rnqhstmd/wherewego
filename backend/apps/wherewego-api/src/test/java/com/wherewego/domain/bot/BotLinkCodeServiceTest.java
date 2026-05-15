package com.wherewego.domain.bot;

import com.wherewego.config.env.BotProperties;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BotLinkCodeServiceTest {

    @Mock
    private BotLinkCodeRepository linkCodeRepository;

    @Mock
    private LinkCodeGenerator linkCodeGenerator;

    @Mock
    private BotProperties botProperties;

    @InjectMocks
    private BotLinkCodeService botLinkCodeService;

    @BeforeEach
    void setUp() {
        BotProperties.LinkCode linkCode = new BotProperties.LinkCode(5, 5);
        when(botProperties.linkCode()).thenReturn(linkCode);
        when(linkCodeRepository.save(any(BotLinkCode.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @DisplayName("코드를 발급할 때,")
    @Nested
    class IssueCode {

        @DisplayName("새 코드를 정상 발급하면, 기존 ACTIVE 를 만료시키고 INSERT 한다 (AC-1).")
        @Test
        void issueCode_newCode_savesActive() {
            // arrange
            when(linkCodeGenerator.generate6Digits()).thenReturn("123456");
            when(linkCodeRepository.existsActiveByCode(eq("123456"), any(Instant.class))).thenReturn(false);

            // act
            BotLinkCodeIssueResult result = botLinkCodeService.issueCode(7L);

            // assert
            verify(linkCodeRepository).expireActiveByUserId(eq(7L));
            verify(linkCodeRepository, times(1)).save(any(BotLinkCode.class));
            assertThat(result.code()).isEqualTo("123456");
            assertThat(result.userId()).isEqualTo(7L);
        }

        @DisplayName("첫 코드가 충돌하고 두 번째가 성공하면, 재시도하여 정상 발급된다 (BR-1).")
        @Test
        void issueCode_collision_retries() {
            // arrange
            when(linkCodeGenerator.generate6Digits()).thenReturn("111111", "222222");
            when(linkCodeRepository.existsActiveByCode(eq("111111"), any(Instant.class))).thenReturn(true);
            when(linkCodeRepository.existsActiveByCode(eq("222222"), any(Instant.class))).thenReturn(false);

            // act
            BotLinkCodeIssueResult result = botLinkCodeService.issueCode(7L);

            // assert
            verify(linkCodeGenerator, times(2)).generate6Digits();
            assertThat(result.code()).isEqualTo("222222");
        }

        @DisplayName("maxRetries 만큼 모두 충돌하면, INTERNAL_ERROR 예외가 발생한다 (BR-1).")
        @Test
        void issueCode_allCollide_throwsInternalError() {
            // arrange
            when(linkCodeGenerator.generate6Digits()).thenReturn("123456");
            when(linkCodeRepository.existsActiveByCode(anyString(), any(Instant.class))).thenReturn(true);

            // act & assert
            assertThatThrownBy(() -> botLinkCodeService.issueCode(7L))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.INTERNAL_ERROR);
            verify(linkCodeGenerator, times(5)).generate6Digits();
        }
    }

    @DisplayName("코드를 소비할 때,")
    @Nested
    class ConsumeCode {

        @DisplayName("ACTIVE 코드를 소비하면, markConsumed 후 save 한다 (AC-4).")
        @Test
        void consumeCode_valid_marksConsumed() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            BotLinkCode active = BotLinkCode.issue(7L, "123456", now.minusSeconds(60), Duration.ofMinutes(5));
            when(linkCodeRepository.findByCode("123456")).thenReturn(Optional.of(active));

            // act
            BotLinkCodeConsumeResult result = botLinkCodeService.consumeCode("123456", now);

            // assert
            assertThat(active.getStatus()).isEqualTo(BotLinkCodeStatus.CONSUMED);
            assertThat(active.getConsumedAt()).isEqualTo(now);
            verify(linkCodeRepository).save(active);
            assertThat(result.userId()).isEqualTo(7L);
            assertThat(result.code()).isEqualTo("123456");
        }

        @DisplayName("이미 CONSUMED 상태면, BOT_LINK_CODE_ALREADY_USED 예외가 발생한다 (AC-5).")
        @Test
        void consumeCode_alreadyUsed_throwsAlreadyUsed() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            BotLinkCode consumed = BotLinkCode.issue(7L, "123456", now.minusSeconds(60), Duration.ofMinutes(5));
            consumed.markConsumed(now.minusSeconds(30));
            when(linkCodeRepository.findByCode("123456")).thenReturn(Optional.of(consumed));

            // act & assert
            assertThatThrownBy(() -> botLinkCodeService.consumeCode("123456", now))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.BOT_LINK_CODE_ALREADY_USED);
        }

        @DisplayName("expiresAt 이 now 보다 이전이면, BOT_LINK_CODE_EXPIRED 예외가 발생한다 (AC-5).")
        @Test
        void consumeCode_expired_throwsExpired() {
            // arrange
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            BotLinkCode expired = BotLinkCode.issue(7L, "123456", now.minus(Duration.ofMinutes(10)), Duration.ofMinutes(5));
            when(linkCodeRepository.findByCode("123456")).thenReturn(Optional.of(expired));

            // act & assert
            assertThatThrownBy(() -> botLinkCodeService.consumeCode("123456", now))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.BOT_LINK_CODE_EXPIRED);
        }
    }
}
