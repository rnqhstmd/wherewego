package com.wherewego.domain.group;

import com.wherewego.config.env.InviteProperties;
import com.wherewego.domain.bot.BotUserMappingService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupMemberServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long GROUP_ID = 10L;
    private static final String TOKEN = "11111111-2222-3333-4444-555555555555";
    private static final String SLUG = "Ab23CdEf";

    @Mock
    private GroupMemberRepository groupMemberRepository;

    @Mock
    private GroupRepository groupRepository;

    @Mock
    private InviteLinkRepository inviteLinkRepository;

    @Mock
    private BotUserMappingService botUserMappingService;

    @Mock
    private InviteLinkSlugGenerator slugGenerator;

    @Mock
    private UserRepository userRepository;

    // record 는 mock 어렵기 때문에 실제 인스턴스를 직접 주입한다.
    private final InviteProperties inviteProperties = new InviteProperties(
            Duration.ofDays(7),
            "http://localhost:3000",
            new InviteProperties.RateLimit(30, 60, 10000));

    @InjectMocks
    private GroupMemberService groupMemberService;

    @BeforeEach
    void setUp() {
        when(groupRepository.save(any(Group.class))).thenAnswer(inv -> inv.getArgument(0));
        when(groupMemberRepository.save(any(GroupMember.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inviteLinkRepository.save(any(InviteLink.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slugGenerator.generate()).thenReturn(SLUG);
        // InviteProperties 가 record 라 @InjectMocks 가 자동 주입하지 못한다 (생성자 시그니처 일치 시는 성공).
        // @RequiredArgsConstructor 가 전체 필드 생성자를 만들고 inviteProperties 도 그 자리에 포함되므로,
        // Mockito 가 매칭 타입을 찾지 못해 null 주입한다. ReflectionTestUtils 로 수동 주입한다.
        ReflectionTestUtils.setField(groupMemberService, "inviteProperties", inviteProperties);
    }

    /** 테스트용 Group 생성 + id 강제 주입. BaseEntity.id 가 final 이라 ReflectionTestUtils 로는 set 불가하므로
     *  id 의존이 필요한 케이스는 그대로 0L 을 사용한다 (mock save 반환값으로 검증). */
    private Group newGroup(String name) {
        return Group.create(name);
    }

    /** soft-deleted Group: deletedAt 강제 주입. */
    private Group newDeletedGroup(String name) {
        Group group = Group.create(name);
        ReflectionTestUtils.setField(group, "deletedAt", ZonedDateTime.now());
        return group;
    }

    @DisplayName("그룹을 생성할 때,")
    @Nested
    class CreateGroup {

        @DisplayName("이름이 정상이면 그룹과 GroupMember 를 저장하고 결과를 반환한다 (AC-1).")
        @Test
        void createGroup_validName_savesGroupAndMember() {
            // arrange
            when(groupMemberRepository.existsActiveByUserId(USER_ID)).thenReturn(false);

            // act
            GroupCreatedResult result = groupMemberService.createGroup(USER_ID, "우리커플");

            // assert
            verify(groupRepository).save(any(Group.class));
            verify(groupMemberRepository).save(any(GroupMember.class));
            assertThat(result.name()).isEqualTo("우리커플");
        }

        @DisplayName("이름이 빈 문자열이면 GROUP_NAME_INVALID 가 발생한다 (AC-2).")
        @Test
        void createGroup_emptyName_throws() {
            // act & assert
            assertThatThrownBy(() -> groupMemberService.createGroup(USER_ID, ""))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NAME_INVALID);
        }

        @DisplayName("이름이 공백만이면 GROUP_NAME_INVALID 가 발생한다 (AC-2).")
        @Test
        void createGroup_blankName_throws() {
            // act & assert
            assertThatThrownBy(() -> groupMemberService.createGroup(USER_ID, "   "))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NAME_INVALID);
        }

        @DisplayName("이름이 30자 초과(31자 입력)면 GROUP_NAME_INVALID 가 발생한다 (AC-3).")
        @Test
        void createGroup_tooLongName_throws() {
            // arrange
            String longName = "a".repeat(31);

            // act & assert
            assertThatThrownBy(() -> groupMemberService.createGroup(USER_ID, longName))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NAME_INVALID);
        }

        @DisplayName("이미 활성 그룹을 보유 중이면 GROUP_ALREADY_ACTIVE 가 발생한다 (AC-10).")
        @Test
        void createGroup_alreadyActive_throws() {
            // arrange
            when(groupMemberRepository.existsActiveByUserId(USER_ID)).thenReturn(true);

            // act & assert
            assertThatThrownBy(() -> groupMemberService.createGroup(USER_ID, "우리커플"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_ALREADY_ACTIVE);
        }

        @DisplayName("GroupMember INSERT 시 DataIntegrityViolation 이 발생하면 GROUP_ALREADY_ACTIVE 로 변환된다 (AC-17).")
        @Test
        void createGroup_dataIntegrityViolation_translatedToAlreadyActive() {
            // arrange
            when(groupMemberRepository.existsActiveByUserId(USER_ID)).thenReturn(false);
            when(groupMemberRepository.save(any(GroupMember.class)))
                    .thenThrow(new DataIntegrityViolationException("partial unique violated"));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.createGroup(USER_ID, "우리커플"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_ALREADY_ACTIVE);
        }
    }

    @DisplayName("초대 링크를 발급할 때,")
    @Nested
    class IssueInviteLink {

        @DisplayName("활성 멤버가 발급하면 expirePending 호출 후 신규 토큰을 반환한다 (AC-4, AC-5).")
        @Test
        void issueInviteLink_activeMember_expiresPendingAndReturnsNewToken() {
            // arrange
            Group group = newGroup("우리커플");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(GROUP_ID, USER_ID, Instant.now())));

            // act
            InviteLinkIssueResult result = groupMemberService.issueInviteLink(USER_ID, GROUP_ID);

            // assert
            verify(inviteLinkRepository).expirePendingByGroupId(eq(GROUP_ID), any(Instant.class));
            verify(inviteLinkRepository).save(any(InviteLink.class));
            assertThat(result.token()).isNotBlank();
            assertThat(result.expiresAt()).isNotNull();
        }

        @DisplayName("비활성 멤버가 발급하면 GROUP_NOT_MEMBER 가 발생한다.")
        @Test
        void issueInviteLink_inactiveMember_throwsNotMember() {
            // arrange
            Group group = newGroup("우리커플");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.empty());

            // act & assert
            assertThatThrownBy(() -> groupMemberService.issueInviteLink(USER_ID, GROUP_ID))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
        }

        @DisplayName("soft-deleted 그룹에 대해 발급하면 GROUP_NOT_MEMBER 가 발생한다.")
        @Test
        void issueInviteLink_deletedGroup_throwsNotMember() {
            // arrange
            Group deletedGroup = newDeletedGroup("우리커플");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(deletedGroup));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.issueInviteLink(USER_ID, GROUP_ID))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
        }
    }

    @DisplayName("초대 링크를 수락할 때,")
    @Nested
    class AcceptInviteLink {

        @DisplayName("정상 토큰을 수락하면 markAccepted 와 GroupMember save 가 호출된다 (AC-6).")
        @Test
        void acceptInviteLink_valid_marksAcceptedAndSavesMember() {
            // arrange
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.existsActiveByUserId(USER_ID)).thenReturn(false);
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(1L);

            // act
            InviteAcceptResult result = groupMemberService.acceptInviteLink(USER_ID, TOKEN);

            // assert
            assertThat(link.getAcceptedAt()).isNotNull();
            verify(groupMemberRepository).save(any(GroupMember.class));
            assertThat(result.groupId()).isEqualTo(group.getId());
            assertThat(result.acceptedAt()).isNotNull();
        }

        @DisplayName("만료된 토큰이면 INVITE_LINK_EXPIRED 가 발생한다 (AC-7).")
        @Test
        void acceptInviteLink_expired_throwsExpired() {
            // arrange: 발급 후 TTL 보다 오래 지난 시점에서 사용
            Instant issuedAt = Instant.now().minus(Duration.ofHours(48));
            InviteLink expired = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(expired));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.INVITE_LINK_EXPIRED);
        }

        @DisplayName("이미 수락된 토큰이면 INVITE_LINK_ALREADY_USED 가 발생한다 (AC-8).")
        @Test
        void acceptInviteLink_alreadyUsed_throwsAlreadyUsed() {
            // arrange
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            link.markAccepted(Instant.now().minus(Duration.ofMinutes(5)));
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.INVITE_LINK_ALREADY_USED);
        }

        @DisplayName("soft-deleted 그룹의 토큰이면 INVITE_LINK_EXPIRED 가 발생한다 (AC-9).")
        @Test
        void acceptInviteLink_deletedGroup_throwsExpired() {
            // arrange
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group deletedGroup = newDeletedGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(deletedGroup));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.INVITE_LINK_EXPIRED);
        }

        @DisplayName("이미 활성 그룹을 보유 중이면 GROUP_ALREADY_ACTIVE 가 발생한다 (AC-10).")
        @Test
        void acceptInviteLink_alreadyActive_throwsAlreadyActive() {
            // arrange
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.existsActiveByUserId(USER_ID)).thenReturn(true);

            // act & assert
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_ALREADY_ACTIVE);
        }

        @DisplayName("발급자가 자기 토큰을 수락하면 INVITE_LINK_SELF_ACCEPT 가 발생한다 (AC-15).")
        @Test
        void acceptInviteLink_selfAccept_throwsSelfAccept() {
            // arrange: inviterId == userId
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.INVITE_LINK_SELF_ACCEPT);
        }

        @DisplayName("정원 2명에 도달했으면 GROUP_CAPACITY_EXCEEDED 가 발생한다 (AC-16).")
        @Test
        void acceptInviteLink_capacityReached_throwsCapacityExceeded() {
            // arrange
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.existsActiveByUserId(USER_ID)).thenReturn(false);
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(2L);

            // act & assert
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_CAPACITY_EXCEEDED);
        }
    }

    @DisplayName("그룹을 탈퇴할 때,")
    @Nested
    class LeaveGroup {

        @DisplayName("정상 탈퇴 시 markLeft 가 호출되고 마지막 멤버가 아니면 group.markDeleted 는 호출되지 않는다 (AC-11).")
        @Test
        void leaveGroup_notLastMember_keepsGroupActive() {
            // arrange
            Group group = newGroup("우리커플");
            GroupMember member = GroupMember.createActive(GROUP_ID, USER_ID, Instant.now().minus(Duration.ofDays(1)));
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(member));
            when(groupMemberRepository.countActiveByGroupId(GROUP_ID)).thenReturn(1L);

            // act
            groupMemberService.leaveGroup(USER_ID, GROUP_ID);

            // assert
            // AC-11: 탈퇴자의 left_at이 기록되어야 한다 (member 객체에 마킹 검증)
            assertThat(member.getLeftAt()).isNotNull();
            assertThat(group.getDeletedAt()).isNull();
            verify(groupRepository, never()).save(any(Group.class));
            verify(inviteLinkRepository, never()).expirePendingByGroupId(eq(GROUP_ID), any(Instant.class));
            // AC-B6: 탈퇴 시 봇 매핑도 해제되어야 한다 (Phase 2.6 B-4)
            verify(botUserMappingService).unlink(USER_ID);
        }

        @DisplayName("마지막 멤버가 탈퇴하면 group.markDeleted 와 expirePending 이 호출된다 (AC-12).")
        @Test
        void leaveGroup_lastMember_markGroupDeleted() {
            // arrange
            Group group = newGroup("우리커플");
            GroupMember member = GroupMember.createActive(GROUP_ID, USER_ID, Instant.now().minus(Duration.ofDays(1)));
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(member));
            when(groupMemberRepository.countActiveByGroupId(GROUP_ID)).thenReturn(0L);

            // act
            groupMemberService.leaveGroup(USER_ID, GROUP_ID);

            // assert
            assertThat(member.getLeftAt()).isNotNull();
            assertThat(group.getDeletedAt()).isNotNull();
            verify(groupRepository).save(group);
            verify(inviteLinkRepository).expirePendingByGroupId(eq(GROUP_ID), any(Instant.class));
            // AC-B6: 마지막 멤버 탈퇴 + 봇 미연동인 경우도 unlink 는 멱등이므로 호출된다.
            verify(botUserMappingService).unlink(USER_ID);
        }

        @DisplayName("비활성 멤버가 탈퇴하려 하면 GROUP_NOT_MEMBER 가 발생한다.")
        @Test
        void leaveGroup_inactiveMember_throwsNotMember() {
            // arrange
            Group group = newGroup("우리커플");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.empty());

            // act & assert
            assertThatThrownBy(() -> groupMemberService.leaveGroup(USER_ID, GROUP_ID))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
            // AC-B6: 멤버십 검증 실패 시 봇 매핑 해제도 호출되지 않아야 한다.
            verify(botUserMappingService, never()).unlink(any());
        }
    }

    @DisplayName("requireActiveMembership 호출 시,")
    @Nested
    class RequireActiveMembership {

        @DisplayName("활성 멤버가 아니면 GROUP_NOT_MEMBER 가 발생한다 (AC-14 부분).")
        @Test
        void requireActiveMembership_inactive_throws() {
            // arrange
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.empty());

            // act & assert
            assertThatThrownBy(() -> groupMemberService.requireActiveMembership(USER_ID, GROUP_ID))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
        }
    }

    @DisplayName("findMyActiveGroup 호출 시,")
    @Nested
    class FindMyActiveGroup {

        @DisplayName("활성 그룹이 없으면 Optional.empty 를 반환한다.")
        @Test
        void findMyActiveGroup_noActive_returnsEmpty() {
            // arrange
            when(groupMemberRepository.findLatestActiveGroupIdByUserId(USER_ID))
                    .thenReturn(Optional.empty());

            // act
            Optional<ActiveGroupInfo> result = groupMemberService.findMyActiveGroup(USER_ID);

            // assert
            assertThat(result).isEmpty();
        }

        @DisplayName("활성 그룹이 있으면 ActiveGroupInfo 를 반환한다.")
        @Test
        void findMyActiveGroup_active_returnsInfo() {
            // arrange
            Group group = newGroup("우리커플");
            when(groupMemberRepository.findLatestActiveGroupIdByUserId(USER_ID))
                    .thenReturn(Optional.of(GROUP_ID));
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

            // act
            Optional<ActiveGroupInfo> result = groupMemberService.findMyActiveGroup(USER_ID);

            // assert
            assertThat(result).isPresent();
            assertThat(result.get().groupId()).isEqualTo(group.getId());
            assertThat(result.get().name()).isEqualTo("우리커플");
        }
    }
}
