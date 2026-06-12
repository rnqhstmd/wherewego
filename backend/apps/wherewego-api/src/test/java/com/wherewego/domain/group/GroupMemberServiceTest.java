package com.wherewego.domain.group;

import com.wherewego.config.env.InviteProperties;
import com.wherewego.config.env.S3Properties;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chat.ChatRoom;
import com.wherewego.domain.chat.ChatRoomRepository;
import com.wherewego.domain.image.AvatarStorage;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
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

    // GC-1: createGroup 이 그룹 방을 함께 생성한다(FR-GC1-1).
    @Mock
    private ChatRoomRepository chatRoomRepository;

    // GP-1: 그룹 이미지 업로드/삭제 시 S3 저장 포트.
    @Mock
    private AvatarStorage avatarStorage;

    // record 는 mock 어렵기 때문에 실제 인스턴스를 직접 주입한다.
    private final InviteProperties inviteProperties = new InviteProperties(
            Duration.ofDays(7),
            "http://localhost:3000",
            new InviteProperties.RateLimit(30, 60, 10000));

    // GP-1: S3Properties 도 record 라 @InjectMocks 가 null 주입 → ReflectionTestUtils 로 수동 주입(listMyGroups 의 toPublicUrl).
    private final S3Properties s3Properties = new S3Properties(
            "test-bucket", "ap-northeast-2", "https://cdn.example.com");

    @InjectMocks
    private GroupMemberService groupMemberService;

    @BeforeEach
    void setUp() {
        when(groupRepository.save(any(Group.class))).thenAnswer(inv -> inv.getArgument(0));
        when(groupMemberRepository.save(any(GroupMember.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inviteLinkRepository.save(any(InviteLink.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slugGenerator.generate()).thenReturn(SLUG);
        // InviteProperties 가 record 라 @InjectMocks 가 자동 주입하지 못한다 (생성자 시그니처 일치 시는 성공).
        // @RequiredArgsConstructor 가 전체 필드 생성자를 만들고 inviteProperties 도 그 자리에 포함되므로,
        // Mockito 가 매칭 타입을 찾지 못해 null 주입한다. ReflectionTestUtils 로 수동 주입한다.
        ReflectionTestUtils.setField(groupMemberService, "inviteProperties", inviteProperties);
        ReflectionTestUtils.setField(groupMemberService, "s3Properties", s3Properties);
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

        @DisplayName("GM-1: 이미 활성 그룹을 보유 중이어도 사전검사 없이 새 그룹을 생성한다 (1인1활성 제약 해제, 사양 변경).")
        @Test
        void createGroup_alreadyActive_stillSucceeds() {
            // arrange : GM-1 으로 existsActiveByUserId 사전검사가 제거됨 — 활성 보유 여부와 무관하게 생성 가능.

            // act
            GroupCreatedResult result = groupMemberService.createGroup(USER_ID, "두 번째 그룹");

            // assert : 그룹/멤버 저장이 그대로 수행된다.
            verify(groupRepository).save(any(Group.class));
            verify(groupMemberRepository).save(any(GroupMember.class));
            assertThat(result.name()).isEqualTo("두 번째 그룹");
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

        @DisplayName("정상 토큰을 수락하면 GroupMember save 가 호출되고 결과를 반환한다 (AC-6).")
        @Test
        void acceptInviteLink_valid_savesMember() {
            // arrange
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(group.getId(), USER_ID))
                    .thenReturn(Optional.empty());
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(1L);

            // act
            InviteAcceptResult result = groupMemberService.acceptInviteLink(USER_ID, TOKEN);

            // assert : IC-1 재사용 모델 — 토큰 소진 UPDATE 없이 group_members INSERT 로 가입한다.
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

        @DisplayName("IC-1: 이미 해당 그룹의 활성 멤버이면 GROUP_ALREADY_MEMBER 가 발생하고 정원 검사/멤버 저장은 수행되지 않는다 (AC-4).")
        @Test
        void acceptInviteLink_alreadyMember_throwsAlreadyMember() {
            // arrange : 사전 가드(findActiveByGroupIdAndUserId)가 활성 멤버를 찾는다.
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(group.getId(), USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(group.getId(), USER_ID, Instant.now())));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_ALREADY_MEMBER);
            // 가드가 정원 검사 앞에 있으므로 멤버 수는 불변(저장 미호출).
            verify(groupMemberRepository, never()).save(any(GroupMember.class));
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

        @DisplayName("GM-1: 이미 다른 활성 그룹을 보유 중이어도 사전검사 없이 초대를 수락한다 (1인1활성 제약 해제, 사양 변경).")
        @Test
        void acceptInviteLink_alreadyActive_stillSucceeds() {
            // arrange : GM-1 으로 existsActiveByUserId 사전검사가 제거됨 — 활성 보유 여부와 무관하게 수락 가능.
            //   IC-1: 같은 그룹의 활성 멤버는 아니므로(다른 그룹 보유) 중복 가드는 empty 를 반환한다.
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(group.getId(), USER_ID))
                    .thenReturn(Optional.empty());
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(1L);

            // act
            InviteAcceptResult result = groupMemberService.acceptInviteLink(USER_ID, TOKEN);

            // assert : 멤버 저장이 그대로 수행된다 (IC-1 재사용 모델).
            verify(groupMemberRepository).save(any(GroupMember.class));
            assertThat(result.groupId()).isEqualTo(group.getId());
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

        @DisplayName("GP-1 FR-8: 정원 8명에 도달했으면 GROUP_CAPACITY_EXCEEDED 가 발생한다 (AC-16, 정원 10→8 축소).")
        @Test
        void acceptInviteLink_capacityReached_throwsCapacityExceeded() {
            // arrange : 정원 8(MAX_GROUP_MEMBERS) 도달 — count==8 이면 >= 검사로 가입 차단.
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(group.getId(), USER_ID))
                    .thenReturn(Optional.empty());
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(8L);

            // act & assert
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_CAPACITY_EXCEEDED);
            // IC-1: 정원 초과 시 멤버 저장 없음(가입 차단). 코드는 TTL 까지 유지된다(Option A).
            verify(groupMemberRepository, never()).save(any(GroupMember.class));
        }

        @DisplayName("GM-1: 동일 그룹 재가입(uq_group_members_pair 위반)이면 GROUP_REJOIN_FORBIDDEN 으로 변환된다 (BR-1).")
        @Test
        void acceptInviteLink_pairConflict_throwsRejoinForbidden() {
            // arrange : save 가 pair 제약 위반 메시지를 가진 DataIntegrityViolation 을 던진다.
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(group.getId(), USER_ID))
                    .thenReturn(Optional.empty());
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(1L);
            when(groupMemberRepository.save(any(GroupMember.class)))
                    .thenThrow(new DataIntegrityViolationException(
                            "could not execute statement; constraint [uq_group_members_pair]"));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_REJOIN_FORBIDDEN);
        }

        @DisplayName("GM-1: pair 외 무결성 위반(FK 등)은 변환하지 않고 원본 DataIntegrityViolationException 을 그대로 전파한다 (else rethrow).")
        @Test
        void acceptInviteLink_otherIntegrityViolation_rethrown() {
            // arrange : save 가 pair 미포함 메시지(예: FK 위반)를 가진 DataIntegrityViolation 을 던진다.
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            DataIntegrityViolationException fkViolation = new DataIntegrityViolationException(
                    "could not execute statement; foreign key constraint [fk_group_members_group_id]");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(group.getId(), USER_ID))
                    .thenReturn(Optional.empty());
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(1L);
            when(groupMemberRepository.save(any(GroupMember.class))).thenThrow(fkViolation);

            // act & assert : CoreException 으로 변환되지 않고 원본 예외가 그대로 전파됨.
            assertThatThrownBy(() -> groupMemberService.acceptInviteLink(USER_ID, TOKEN))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isSameAs(fkViolation);
        }
    }

    @DisplayName("slug 로 초대 링크를 미리볼 때,")
    @Nested
    class PreviewBySlug {

        @DisplayName("IC-1: 유효 코드 + 정원 미도달이면 그룹명/초대자/만료시각 미리보기를 반환한다.")
        @Test
        void previewBySlug_valid_returnsPreview() {
            // arrange
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofDays(7));
            Group group = newGroup("우리커플");
            UserModel inviter = UserModel.create(10000003L, "초대자닉", null);
            when(inviteLinkRepository.findActiveBySlug(eq(SLUG), any(Instant.class))).thenReturn(Optional.of(link));
            when(groupRepository.findById(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(1L);
            when(userRepository.findById(link.getInviterId())).thenReturn(Optional.of(inviter));

            // act
            InviteLinkPreviewResult result = groupMemberService.previewBySlug(SLUG);

            // assert
            assertThat(result.token()).isEqualTo(TOKEN);
            assertThat(result.groupName()).isEqualTo("우리커플");
            assertThat(result.inviterNickname()).isEqualTo("초대자닉");
            assertThat(result.expiresAt()).isNotNull();
        }

        @DisplayName("IC-1(D4)+GP-1 FR-8: 유효 코드이지만 그룹 정원(8)에 도달했으면 GROUP_CAPACITY_EXCEEDED 가 발생한다 (AC-6, 정원 10→8 축소).")
        @Test
        void previewBySlug_capacityReached_throwsCapacityExceeded() {
            // arrange : 정원 8(MAX_GROUP_MEMBERS) 도달.
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofDays(7));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findActiveBySlug(eq(SLUG), any(Instant.class))).thenReturn(Optional.of(link));
            when(groupRepository.findById(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(8L);

            // act & assert : 만료/없음의 NOT_FOUND 가 아니라 정원 초과로 구분 응답한다.
            assertThatThrownBy(() -> groupMemberService.previewBySlug(SLUG))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_CAPACITY_EXCEEDED);
        }

        @DisplayName("GP-1 FR-8: 정원 경계 — 활성 7명(정원 8 미만)이면 수락이 성공한다 (7명 그룹 가입 가능, AC-6).")
        @Test
        void acceptInviteLink_sevenMembers_succeeds() {
            // arrange : count==7 < MAX(8) → 가입 허용.
            Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));
            InviteLink link = InviteLink.issue(GROUP_ID, OTHER_USER_ID, TOKEN, SLUG, issuedAt, Duration.ofHours(24));
            Group group = newGroup("우리커플");
            when(inviteLinkRepository.findByToken(TOKEN)).thenReturn(Optional.of(link));
            when(groupRepository.findByIdForUpdate(link.getGroupId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(group.getId(), USER_ID))
                    .thenReturn(Optional.empty());
            when(groupMemberRepository.countActiveByGroupId(group.getId())).thenReturn(7L);

            // act
            InviteAcceptResult result = groupMemberService.acceptInviteLink(USER_ID, TOKEN);

            // assert : 정원 미달이므로 가입(멤버 저장) 성공.
            verify(groupMemberRepository).save(any(GroupMember.class));
            assertThat(result.groupId()).isEqualTo(group.getId());
        }

        @DisplayName("IC-1: 만료/존재하지 않는 코드이면 INVITE_LINK_NOT_FOUND 가 발생한다.")
        @Test
        void previewBySlug_expiredOrNotFound_throwsNotFound() {
            // arrange : findActiveBySlug(slug, now) 가 만료/없음으로 empty 를 반환.
            when(inviteLinkRepository.findActiveBySlug(eq(SLUG), any(Instant.class))).thenReturn(Optional.empty());

            // act & assert
            assertThatThrownBy(() -> groupMemberService.previewBySlug(SLUG))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.INVITE_LINK_NOT_FOUND);
        }
    }

    @DisplayName("그룹을 탈퇴할 때,")
    @Nested
    class LeaveGroup {

        @DisplayName("정상 탈퇴 시 markLeft 가 호출되고 마지막 멤버가 아니면 group.markDeleted 는 호출되지 않는다 (AC-11). " +
                "Phase 11 PR-A: 탈퇴 시점에 미수락 초대를 일괄 만료한다 (R-2).")
        @Test
        void leaveGroup_notLastMember_keepsGroupActive() {
            // arrange
            Group group = newGroup("우리커플");
            GroupMember member = GroupMember.createActive(GROUP_ID, USER_ID, Instant.now().minus(Duration.ofDays(1)));
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(member));
            when(groupMemberRepository.countActiveByGroupId(GROUP_ID)).thenReturn(1L);
            // GM-1: 탈퇴 후 사용자의 잔여 활성 그룹이 0개라야 unlink 가 호출된다.
            when(groupMemberRepository.listActiveGroupIdsByUserId(USER_ID)).thenReturn(List.of());

            // act
            groupMemberService.leaveGroup(USER_ID, GROUP_ID);

            // assert
            // AC-11: 탈퇴자의 left_at이 기록되어야 한다 (member 객체에 마킹 검증)
            assertThat(member.getLeftAt()).isNotNull();
            assertThat(group.getDeletedAt()).isNull();
            verify(groupRepository, never()).save(any(Group.class));
            // Phase 11 PR-A (R-2): 탈퇴 시점에 미수락 초대를 일괄 만료한다.
            verify(inviteLinkRepository).expirePendingByGroupId(eq(GROUP_ID), any(Instant.class));
            // AC-B6: 탈퇴 시 봇 매핑도 해제되어야 한다 (Phase 2.6 B-4). GM-1: 잔여 활성 그룹 0개라 unlink.
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
            // GM-1: 탈퇴 후 사용자의 잔여 활성 그룹이 0개라야 unlink 가 호출된다.
            when(groupMemberRepository.listActiveGroupIdsByUserId(USER_ID)).thenReturn(List.of());

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

        @DisplayName("GM-1: 1개 그룹만 탈퇴해도 잔여 활성 그룹이 남아있으면 봇 매핑을 해제하지 않는다.")
        @Test
        void leaveGroup_userHasOtherActiveGroups_doesNotUnlinkBot() {
            // arrange : 탈퇴 후 사용자의 잔여 활성 그룹이 비어있지 않다(다른 그룹 보유).
            Group group = newGroup("우리커플");
            GroupMember member = GroupMember.createActive(GROUP_ID, USER_ID, Instant.now().minus(Duration.ofDays(1)));
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(member));
            when(groupMemberRepository.countActiveByGroupId(GROUP_ID)).thenReturn(1L);
            when(groupMemberRepository.listActiveGroupIdsByUserId(USER_ID)).thenReturn(List.of(20L));

            // act
            groupMemberService.leaveGroup(USER_ID, GROUP_ID);

            // assert : 봇 매핑은 group 무관 user 단위라 잔여 그룹에서 챗봇 계속 사용 — unlink 호출 안 됨.
            assertThat(member.getLeftAt()).isNotNull();
            verify(botUserMappingService, never()).unlink(USER_ID);
        }

        @DisplayName("GM-1: 사용자의 마지막 활성 그룹을 탈퇴하면(잔여 0개) 봇 매핑을 해제한다.")
        @Test
        void leaveGroup_userLastActiveGroup_unlinksBot() {
            // arrange : 탈퇴 후 사용자의 잔여 활성 그룹이 0개다.
            Group group = newGroup("우리커플");
            GroupMember member = GroupMember.createActive(GROUP_ID, USER_ID, Instant.now().minus(Duration.ofDays(1)));
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(member));
            when(groupMemberRepository.countActiveByGroupId(GROUP_ID)).thenReturn(1L);
            when(groupMemberRepository.listActiveGroupIdsByUserId(USER_ID)).thenReturn(List.of());

            // act
            groupMemberService.leaveGroup(USER_ID, GROUP_ID);

            // assert : 마지막 활성 그룹 탈퇴이므로 user 단위 봇 매핑을 끊는다.
            assertThat(member.getLeftAt()).isNotNull();
            verify(botUserMappingService).unlink(USER_ID);
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

    @DisplayName("그룹원 목록을 조회할 때,")
    @Nested
    class ListMembers {

        @DisplayName("정렬된 첫 항목(joined_at 최소)만 방장(isOwner=true)으로 마킹하고 나머지는 false 다 (GM-2). "
                + "GP-1 FR-9: 프사 썸네일 키 우선 → 카카오 URL 폴백 → null 의 유효 프사 URL 이 합성된다.")
        @Test
        void listMembers_marksFirstAsOwner() {
            // arrange : repo 가 joined_at ASC, id ASC 정렬된 목록을 반환(첫 항목 = 방장).
            //   방장 = 업로드 썸네일 키 보유(→ 공개 URL), 멤버 = 키 없이 카카오 URL 만(→ 카카오 URL 폴백).
            Instant base = Instant.now();
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(GROUP_ID, USER_ID, base)));
            when(groupMemberRepository.listActiveMembersByGroupId(GROUP_ID)).thenReturn(List.of(
                    new GroupMemberInfo(USER_ID, "방장", base, 1L, "users/7/avatar/x_thumb.webp", "https://kakao/p.png"),
                    new GroupMemberInfo(OTHER_USER_ID, "멤버", base.plus(Duration.ofDays(1)), 2L, null, "https://kakao/q.png")));

            // act
            List<GroupMemberService.GroupMemberResult> result =
                    groupMemberService.listMembers(USER_ID, GROUP_ID);

            // assert
            assertThat(result).hasSize(2);
            assertThat(result.get(0).userId()).isEqualTo(USER_ID);
            assertThat(result.get(0).isOwner()).isTrue();
            // 썸네일 키가 있으면 그 공개 URL(s3Properties.publicBaseUrl + key).
            assertThat(result.get(0).profileImageUrl())
                    .isEqualTo("https://cdn.example.com/users/7/avatar/x_thumb.webp");
            assertThat(result.get(1).userId()).isEqualTo(OTHER_USER_ID);
            assertThat(result.get(1).isOwner()).isFalse();
            // 썸네일 키가 없으면 카카오 URL 폴백.
            assertThat(result.get(1).profileImageUrl()).isEqualTo("https://kakao/q.png");
        }

        @DisplayName("비멤버가 조회하면 GROUP_NOT_MEMBER 가 발생한다.")
        @Test
        void listMembers_notMember_throws() {
            // arrange
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.empty());

            // act & assert
            assertThatThrownBy(() -> groupMemberService.listMembers(USER_ID, GROUP_ID))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
        }
    }

    @DisplayName("그룹명을 수정할 때,")
    @Nested
    class RenameGroup {

        @DisplayName("활성 멤버가 정상 이름으로 수정하면 group.rename 후 save 가 호출된다 (GM-2).")
        @Test
        void renameGroup_validName_renamesAndSaves() {
            // arrange
            Group group = newGroup("옛이름");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(GROUP_ID, USER_ID, Instant.now())));

            // act
            groupMemberService.renameGroup(USER_ID, GROUP_ID, "새이름");

            // assert
            assertThat(group.getName()).isEqualTo("새이름");
            verify(groupRepository).save(group);
        }

        @DisplayName("이름을 trim 한 뒤 빈 문자열이면 GROUP_NAME_INVALID 가 발생한다.")
        @Test
        void renameGroup_blankName_throws() {
            // arrange
            Group group = newGroup("옛이름");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(GROUP_ID, USER_ID, Instant.now())));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.renameGroup(USER_ID, GROUP_ID, "   "))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NAME_INVALID);
            verify(groupRepository, never()).save(any(Group.class));
        }

        @DisplayName("이름이 30자 초과(31자)면 GROUP_NAME_INVALID 가 발생한다.")
        @Test
        void renameGroup_tooLongName_throws() {
            // arrange
            Group group = newGroup("옛이름");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(GROUP_ID, USER_ID, Instant.now())));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.renameGroup(USER_ID, GROUP_ID, "a".repeat(31)))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NAME_INVALID);
        }

        @DisplayName("비멤버가 수정하면 GROUP_NOT_MEMBER 가 발생한다.")
        @Test
        void renameGroup_notMember_throws() {
            // arrange
            Group group = newGroup("옛이름");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.empty());

            // act & assert
            assertThatThrownBy(() -> groupMemberService.renameGroup(USER_ID, GROUP_ID, "새이름"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
        }

        @DisplayName("soft-deleted 그룹을 수정하려 하면 GROUP_NOT_MEMBER 가 발생한다.")
        @Test
        void renameGroup_deletedGroup_throws() {
            // arrange
            Group deletedGroup = newDeletedGroup("옛이름");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(deletedGroup));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.renameGroup(USER_ID, GROUP_ID, "새이름"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
        }
    }

    @DisplayName("그룹을 삭제할 때,")
    @Nested
    class DeleteGroup {

        @DisplayName("방장(첫 항목)이 삭제하면 전원 markLeft + group soft delete + expirePending 이 호출된다 (GM-2).")
        @Test
        void deleteGroup_owner_marksAllLeftAndSoftDeletes() {
            // arrange : USER_ID 가 방장(첫 항목), OTHER_USER_ID 가 멤버.
            Instant base = Instant.now();
            Group group = newGroup("우리커플");
            GroupMember ownerMember = GroupMember.createActive(GROUP_ID, USER_ID, base);
            GroupMember otherMember = GroupMember.createActive(GROUP_ID, OTHER_USER_ID, base.plus(Duration.ofDays(1)));
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.listActiveMembersByGroupId(GROUP_ID)).thenReturn(List.of(
                    new GroupMemberInfo(USER_ID, "방장", base, 1L, null, null),
                    new GroupMemberInfo(OTHER_USER_ID, "멤버", base.plus(Duration.ofDays(1)), 2L, null, null)));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(ownerMember));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, OTHER_USER_ID))
                    .thenReturn(Optional.of(otherMember));
            when(groupMemberRepository.listActiveGroupIdsByUserId(USER_ID)).thenReturn(List.of());
            when(groupMemberRepository.listActiveGroupIdsByUserId(OTHER_USER_ID)).thenReturn(List.of());

            // act
            groupMemberService.deleteGroup(USER_ID, GROUP_ID);

            // assert : 전원 탈퇴 + 그룹 soft delete + 초대 만료.
            assertThat(ownerMember.getLeftAt()).isNotNull();
            assertThat(otherMember.getLeftAt()).isNotNull();
            assertThat(group.getDeletedAt()).isNotNull();
            verify(groupRepository).save(group);
            verify(inviteLinkRepository).expirePendingByGroupId(eq(GROUP_ID), any(Instant.class));
            // 두 멤버 모두 잔여 활성 그룹 0개 → 봇 매핑 해제.
            verify(botUserMappingService).unlink(USER_ID);
            verify(botUserMappingService).unlink(OTHER_USER_ID);
        }

        @DisplayName("방장이 아닌 멤버가 삭제하려 하면 GROUP_OWNER_REQUIRED 가 발생하고 아무 변경도 없다.")
        @Test
        void deleteGroup_notOwner_throwsOwnerRequired() {
            // arrange : 첫 항목(방장)은 OTHER_USER_ID, 요청자는 USER_ID(비방장).
            Instant base = Instant.now();
            Group group = newGroup("우리커플");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.listActiveMembersByGroupId(GROUP_ID)).thenReturn(List.of(
                    new GroupMemberInfo(OTHER_USER_ID, "방장", base, 1L, null, null),
                    new GroupMemberInfo(USER_ID, "멤버", base.plus(Duration.ofDays(1)), 2L, null, null)));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.deleteGroup(USER_ID, GROUP_ID))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_OWNER_REQUIRED);
            assertThat(group.getDeletedAt()).isNull();
            verify(groupRepository, never()).save(any(Group.class));
            verify(botUserMappingService, never()).unlink(any());
        }

        @DisplayName("방장 자동 승계: 방장이 탈퇴한 뒤 다음 최선임이 방장이 되어 삭제할 수 있다 (GM-2).")
        @Test
        void deleteGroup_ownerSuccession_nextSeniorBecomesOwner() {
            // arrange : 원래 방장(joined_at 최소)이 탈퇴해 활성 목록의 첫 항목이 OTHER_USER_ID 가 됐다.
            //   조회 시점 계산이라 별도 승계 로직 없이 OTHER_USER_ID 가 방장으로 삭제 가능.
            Instant base = Instant.now();
            Group group = newGroup("우리커플");
            GroupMember otherMember = GroupMember.createActive(GROUP_ID, OTHER_USER_ID, base.plus(Duration.ofDays(1)));
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.listActiveMembersByGroupId(GROUP_ID)).thenReturn(List.of(
                    new GroupMemberInfo(OTHER_USER_ID, "승계방장", base.plus(Duration.ofDays(1)), 2L, null, null)));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, OTHER_USER_ID))
                    .thenReturn(Optional.of(otherMember));
            when(groupMemberRepository.listActiveGroupIdsByUserId(OTHER_USER_ID)).thenReturn(List.of());

            // act : 승계된 방장 OTHER_USER_ID 가 삭제.
            groupMemberService.deleteGroup(OTHER_USER_ID, GROUP_ID);

            // assert
            assertThat(otherMember.getLeftAt()).isNotNull();
            assertThat(group.getDeletedAt()).isNotNull();
            verify(groupRepository).save(group);
        }

        @DisplayName("soft-deleted 그룹을 삭제하려 하면 GROUP_NOT_MEMBER 가 발생한다.")
        @Test
        void deleteGroup_deletedGroup_throws() {
            // arrange
            Group deletedGroup = newDeletedGroup("우리커플");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(deletedGroup));

            // act & assert
            assertThatThrownBy(() -> groupMemberService.deleteGroup(USER_ID, GROUP_ID))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
        }

        @DisplayName("멤버가 다른 활성 그룹을 보유하면 해당 멤버의 봇 매핑은 해제하지 않는다.")
        @Test
        void deleteGroup_memberHasOtherGroups_doesNotUnlinkThatMember() {
            // arrange : OTHER_USER_ID 는 잔여 활성 그룹 보유 → unlink 제외. 방장 USER_ID 는 0개 → unlink.
            Instant base = Instant.now();
            Group group = newGroup("우리커플");
            GroupMember ownerMember = GroupMember.createActive(GROUP_ID, USER_ID, base);
            GroupMember otherMember = GroupMember.createActive(GROUP_ID, OTHER_USER_ID, base.plus(Duration.ofDays(1)));
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.listActiveMembersByGroupId(GROUP_ID)).thenReturn(List.of(
                    new GroupMemberInfo(USER_ID, "방장", base, 1L, null, null),
                    new GroupMemberInfo(OTHER_USER_ID, "멤버", base.plus(Duration.ofDays(1)), 2L, null, null)));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(ownerMember));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, OTHER_USER_ID))
                    .thenReturn(Optional.of(otherMember));
            when(groupMemberRepository.listActiveGroupIdsByUserId(USER_ID)).thenReturn(List.of());
            when(groupMemberRepository.listActiveGroupIdsByUserId(OTHER_USER_ID)).thenReturn(List.of(99L));

            // act
            groupMemberService.deleteGroup(USER_ID, GROUP_ID);

            // assert
            verify(botUserMappingService).unlink(USER_ID);
            verify(botUserMappingService, never()).unlink(OTHER_USER_ID);
        }
    }

    @DisplayName("listMyGroups 호출 시,")
    @Nested
    class ListMyGroups {

        @DisplayName("GM-1: 활성 그룹이 없으면 빈 리스트를 반환한다 (포트 위임 검증).")
        @Test
        void listMyGroups_noGroups_returnsEmptyList() {
            // arrange
            when(groupMemberRepository.listActiveGroupSummariesByUserId(USER_ID))
                    .thenReturn(List.of());

            // act
            List<GroupSummary> result = groupMemberService.listMyGroups(USER_ID);

            // assert
            assertThat(result).isEmpty();
            verify(groupMemberRepository).listActiveGroupSummariesByUserId(USER_ID);
        }

        @DisplayName("GM-1: 활성 그룹이 여러 개이면 포트가 반환한 목록을 그대로 위임한다.")
        @Test
        void listMyGroups_multipleGroups_delegatesPortResult() {
            // arrange
            // GP-1: GroupSummary 에 imageUrl/imageThumbUrl 추가(null = 이미지 없음 → toPublicUrl 미접근).
            List<GroupSummary> summaries = List.of(
                    new GroupSummary(10L, "여행팀", ZonedDateTime.now(), 3L, null, null),
                    new GroupSummary(20L, "맛집팀", ZonedDateTime.now(), 2L, null, null));
            when(groupMemberRepository.listActiveGroupSummariesByUserId(USER_ID))
                    .thenReturn(summaries);

            // act
            List<GroupSummary> result = groupMemberService.listMyGroups(USER_ID);

            // assert
            assertThat(result).hasSize(2);
            assertThat(result).extracting(GroupSummary::groupId).containsExactly(10L, 20L);
        }
    }

    @DisplayName("listMyGroupsWithMembers 호출 시,")
    @Nested
    class ListMyGroupsWithMembers {

        @DisplayName("GP-1 FR-4: 그룹 목록(이미지 URL)과 멤버 프리뷰(가입순)를 조립한다 — 멤버 IN 쿼리 1회.")
        @Test
        void listMyGroupsWithMembers_assemblesSummariesAndMembers() {
            // arrange : 그룹 1개 + 활성 멤버 2명(가입순 ASC). 멤버 IN 쿼리는 가입순 정렬된 raw 행을 반환.
            when(groupMemberRepository.listActiveGroupSummariesByUserId(USER_ID)).thenReturn(List.of(
                    new GroupSummary(10L, "여행팀", ZonedDateTime.now(), 2L,
                            "groups/10/avatar/x.jpg", "groups/10/avatar/x_thumb.webp")));
            when(groupMemberRepository.listActiveMembersByGroupIds(List.of(10L))).thenReturn(List.of(
                    new GroupMemberAvatarRow(10L, USER_ID, "지민", "users/7/avatar/p_thumb.webp", "https://kakao/p.png"),
                    new GroupMemberAvatarRow(10L, OTHER_USER_ID, "수아", null, "https://kakao/q.png")));

            // act
            List<GroupListItem> result = groupMemberService.listMyGroupsWithMembers(USER_ID);

            // assert : 그룹 1개 + 이미지 공개 URL + 멤버 가입순 + 유효 프사 URL 규칙(키>카카오).
            assertThat(result).hasSize(1);
            GroupListItem item = result.get(0);
            assertThat(item.summary().groupId()).isEqualTo(10L);
            assertThat(item.summary().imageThumbUrl())
                    .isEqualTo("https://cdn.example.com/groups/10/avatar/x_thumb.webp");
            assertThat(item.members()).extracting(GroupMemberPreview::userId)
                    .containsExactly(USER_ID, OTHER_USER_ID); // 가입순 보존
            assertThat(item.members().get(0).profileImageUrl())
                    .isEqualTo("https://cdn.example.com/users/7/avatar/p_thumb.webp");
            assertThat(item.members().get(1).profileImageUrl()).isEqualTo("https://kakao/q.png");
        }

        @DisplayName("GP-1: 활성 그룹이 없으면 빈 리스트를 반환하고 멤버 IN 쿼리를 호출하지 않는다.")
        @Test
        void listMyGroupsWithMembers_noGroups_returnsEmptyAndSkipsMemberQuery() {
            // arrange
            when(groupMemberRepository.listActiveGroupSummariesByUserId(USER_ID)).thenReturn(List.of());

            // act
            List<GroupListItem> result = groupMemberService.listMyGroupsWithMembers(USER_ID);

            // assert
            assertThat(result).isEmpty();
            verify(groupMemberRepository, never()).listActiveMembersByGroupIds(any());
        }
    }

    @DisplayName("그룹 대표 이미지를 업로드/제거할 때,")
    @Nested
    class GroupImage {

        @DisplayName("GP-1 FR-1/FR-2: 활성 멤버가 업로드하면 S3 저장 + updateImage + 공개 URL 응답이다(이전 이미지 없으면 회수 미호출).")
        @Test
        void updateGroupImage_member_storesAndReturnsUrls() {
            // arrange : 이미지 없던 그룹.
            Group group = newGroup("여행팀");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(GROUP_ID, USER_ID, Instant.now())));
            when(avatarStorage.store(eq("groups/" + GROUP_ID + "/avatar"), any(byte[].class), eq("image/jpeg")))
                    .thenReturn(new AvatarStorage.StoredAvatar(
                            "groups/10/avatar/u.jpg", "groups/10/avatar/u_thumb.webp"));

            // act
            GroupMemberService.GroupImageResult result =
                    groupMemberService.updateGroupImage(USER_ID, GROUP_ID, new byte[]{1, 2, 3}, "image/jpeg");

            // assert : 엔티티 키 갱신 + 공개 URL + 이전 키 없으니 회수 미호출.
            assertThat(group.getImageKey()).isEqualTo("groups/10/avatar/u.jpg");
            assertThat(result.imageThumbUrl()).isEqualTo("https://cdn.example.com/groups/10/avatar/u_thumb.webp");
            verify(groupRepository).save(group);
            verify(avatarStorage, never()).deleteQuietly(any(), any());
        }

        @DisplayName("GP-1 FR-2: 기존 이미지가 있으면 교체 후 이전 객체를 best-effort 회수한다(키 교체).")
        @Test
        void updateGroupImage_replacesAndDeletesOldKeys() {
            // arrange : 이미 이미지가 있던 그룹.
            Group group = newGroup("여행팀");
            group.updateImage("groups/10/avatar/old.jpg", "groups/10/avatar/old_thumb.webp");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(GROUP_ID, USER_ID, Instant.now())));
            when(avatarStorage.store(any(), any(byte[].class), any()))
                    .thenReturn(new AvatarStorage.StoredAvatar(
                            "groups/10/avatar/new.jpg", "groups/10/avatar/new_thumb.webp"));

            // act
            groupMemberService.updateGroupImage(USER_ID, GROUP_ID, new byte[]{1}, "image/png");

            // assert : 새 키로 갱신 + 이전 키 회수.
            assertThat(group.getImageKey()).isEqualTo("groups/10/avatar/new.jpg");
            verify(avatarStorage).deleteQuietly("groups/10/avatar/old.jpg", "groups/10/avatar/old_thumb.webp");
        }

        @DisplayName("GP-1: 비멤버가 업로드하면 GROUP_NOT_MEMBER 이고 S3 저장은 호출되지 않는다.")
        @Test
        void updateGroupImage_notMember_throwsAndSkipsStore() {
            // arrange
            Group group = newGroup("여행팀");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.empty());

            // act & assert
            assertThatThrownBy(() -> groupMemberService.updateGroupImage(USER_ID, GROUP_ID, new byte[]{1}, "image/jpeg"))
                    .isInstanceOf(CoreException.class)
                    .extracting("errorType")
                    .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
            verify(avatarStorage, never()).store(any(), any(), any());
        }

        @DisplayName("GP-1 FR-2: 활성 멤버가 제거하면 clearImage + S3 객체 회수 + 두 URL null 응답이다.")
        @Test
        void clearGroupImage_member_clearsAndDeletes() {
            // arrange : 이미지 보유 그룹.
            Group group = newGroup("여행팀");
            group.updateImage("groups/10/avatar/old.jpg", "groups/10/avatar/old_thumb.webp");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(GROUP_ID, USER_ID, Instant.now())));

            // act
            GroupMemberService.GroupImageResult result = groupMemberService.clearGroupImage(USER_ID, GROUP_ID);

            // assert : 키 비움 + 회수 + null 응답.
            assertThat(group.getImageKey()).isNull();
            assertThat(result.imageUrl()).isNull();
            assertThat(result.imageThumbUrl()).isNull();
            verify(avatarStorage).deleteQuietly("groups/10/avatar/old.jpg", "groups/10/avatar/old_thumb.webp");
            verify(groupRepository).save(group);
        }

        @DisplayName("GP-1: 이미지가 없던 그룹을 제거하면 멱등 성공(S3 회수 미호출).")
        @Test
        void clearGroupImage_noImage_isIdempotent() {
            // arrange : 이미지 없는 그룹.
            Group group = newGroup("여행팀");
            when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
            when(groupMemberRepository.findActiveByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(GroupMember.createActive(GROUP_ID, USER_ID, Instant.now())));

            // act
            GroupMemberService.GroupImageResult result = groupMemberService.clearGroupImage(USER_ID, GROUP_ID);

            // assert : null 응답 + S3 호출 없음.
            assertThat(result.imageUrl()).isNull();
            verify(avatarStorage, never()).deleteQuietly(any(), any());
        }
    }
}
