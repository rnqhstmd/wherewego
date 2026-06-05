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
        // Default: no group, no bot mapping.
        // GM-1: existsActiveByUserId 포트가 삭제되어 stub 제거. getStatus 는 findLatestActiveGroupIdByUserId
        //   하나로 존재 여부 + 그룹 ID 를 동시에 얻으므로, 기본값은 그 호출이 empty 를 반환하면 충분하다.
        lenient().when(groupMemberRepository.findLatestActiveGroupIdByUserId(USER_ID)).thenReturn(Optional.empty());
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

    @DisplayName("GM-1: 다중 활성 그룹이어도 hasActiveGroup=true, memberCount 는 최신 활성 그룹(id DESC) 기준이다 (웹 호환 BR-6).")
    @Test
    void getStatus_multipleGroups_usesLatestGroupMemberCount() {
        // arrange : findLatestActiveGroupIdByUserId 가 최신 그룹(GROUP_ID)을 반환하고,
        //   그 그룹의 멤버수만 countActiveByGroupId 로 조회한다 (다중 그룹이어도 단일 경로 동작 유지).
        when(groupMemberRepository.findLatestActiveGroupIdByUserId(USER_ID)).thenReturn(Optional.of(GROUP_ID));
        when(groupMemberRepository.countActiveByGroupId(GROUP_ID)).thenReturn(3L);

        // act
        OnboardingStatus status = userOnboardingService.getStatus(USER_ID);

        // assert
        assertThat(status.hasActiveGroup()).isTrue();
        assertThat(status.activeGroupMemberCount()).isEqualTo(3L);
    }
}
