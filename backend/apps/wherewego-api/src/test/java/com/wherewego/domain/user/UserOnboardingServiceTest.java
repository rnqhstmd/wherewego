package com.wherewego.domain.user;

import com.wherewego.domain.bot.BotUserMapping;
import com.wherewego.domain.bot.BotUserMappingRepository;
import com.wherewego.domain.group.GroupMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserOnboardingServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long GROUP_ID = 10L;

    @Mock
    private GroupMemberRepository groupMemberRepository;

    @Mock
    private BotUserMappingRepository botUserMappingRepository;

    @InjectMocks
    private UserOnboardingService userOnboardingService;

    @BeforeEach
    void setUp() {
        // Default: no group, no bot mapping
        lenient().when(groupMemberRepository.existsActiveByUserId(USER_ID)).thenReturn(false);
        lenient().when(botUserMappingRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
    }

    @DisplayName("그룹 없음 + 봇 매핑 없음이면 hasActiveGroup=false, memberCount=0, hasBotMapping=false.")
    @Test
    void getStatus_noGroupNoBot_returnsAllFalse() {
        // act
        OnboardingStatus status = userOnboardingService.getStatus(USER_ID);

        // assert
        assertThat(status.hasActiveGroup()).isFalse();
        assertThat(status.activeGroupMemberCount()).isZero();
        assertThat(status.hasBotMapping()).isFalse();
    }

    @DisplayName("혼자 그룹 + 봇 미연동이면 hasActiveGroup=true, memberCount=1, hasBotMapping=false.")
    @Test
    void getStatus_soloGroup_returnsMemberCount1() {
        // arrange
        when(groupMemberRepository.existsActiveByUserId(USER_ID)).thenReturn(true);
        when(groupMemberRepository.findLatestActiveGroupIdByUserId(USER_ID)).thenReturn(Optional.of(GROUP_ID));
        when(groupMemberRepository.countActiveByGroupId(GROUP_ID)).thenReturn(1L);

        // act
        OnboardingStatus status = userOnboardingService.getStatus(USER_ID);

        // assert
        assertThat(status.hasActiveGroup()).isTrue();
        assertThat(status.activeGroupMemberCount()).isEqualTo(1L);
        assertThat(status.hasBotMapping()).isFalse();
    }

    @DisplayName("짝꿍 합류 완료 + 봇 연동이면 hasActiveGroup=true, memberCount=2, hasBotMapping=true.")
    @Test
    void getStatus_pairedGroupWithBot_returnsAllTrue() {
        // arrange
        when(groupMemberRepository.existsActiveByUserId(USER_ID)).thenReturn(true);
        when(groupMemberRepository.findLatestActiveGroupIdByUserId(USER_ID)).thenReturn(Optional.of(GROUP_ID));
        when(groupMemberRepository.countActiveByGroupId(GROUP_ID)).thenReturn(2L);
        BotUserMapping mapping = BotUserMapping.link("bot-user-key", USER_ID, java.time.Instant.now());
        when(botUserMappingRepository.findByUserId(USER_ID)).thenReturn(Optional.of(mapping));

        // act
        OnboardingStatus status = userOnboardingService.getStatus(USER_ID);

        // assert
        assertThat(status.hasActiveGroup()).isTrue();
        assertThat(status.activeGroupMemberCount()).isEqualTo(2L);
        assertThat(status.hasBotMapping()).isTrue();
    }
}
