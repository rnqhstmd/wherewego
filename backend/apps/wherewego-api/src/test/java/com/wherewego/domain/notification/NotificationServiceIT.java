package com.wherewego.domain.notification;

import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.InviteLinkIssueResult;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.notification.NotificationJpaRepository;
import com.wherewego.infrastructure.notification.NotificationPinJpaRepository;
import com.wherewego.infrastructure.pin.PinJpaRepository;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NotificationService} 통합 테스트 (Phase 8 — 12단계).
 *
 * <p>fan-out 규칙, empty no-op, markAllRead, soft-delete 핀 detail, NOT_FOUND 권한 검증을
 * Testcontainers PostgreSQL + 실제 트랜잭션 위에서 검증한다.</p>
 *
 * <p>{@link PinServiceIT} 의 그룹/멤버/핀 픽스처 패턴을 그대로 따른다 (create → invite → accept).
 * 테스트 클래스에 {@code @Transactional} 을 두지 않음: {@link NotificationService} 의
 * fan-out 메서드가 자체 {@code @Transactional} 로 커밋해야 데이터가 실제로 영속되며,
 * 격리는 {@link #truncateAll()} 로 보장한다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class NotificationServiceIT {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationJpaRepository notificationJpa;

    @Autowired
    private NotificationPinJpaRepository notificationPinJpa;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private GroupMemberService groupMemberService;

    @Autowired
    private PinRepository pinRepository;

    @Autowired
    private PinJpaRepository pinJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userAId;  // 등록자(self)
    private Long userBId;  // 같은 그룹의 활성 상대방
    private Long userCId;  // 무관 사용자 (그룹 비멤버)
    private Long groupId;

    @BeforeEach
    void setUp() {
        truncateAll();

        UserModel userA = userJpaRepository.save(UserModel.create(40000001L, "userA", null));
        UserModel userB = userJpaRepository.save(UserModel.create(40000002L, "userB", null));
        UserModel userC = userJpaRepository.save(UserModel.create(40000003L, "userC", null));
        this.userAId = userA.getId();
        this.userBId = userB.getId();
        this.userCId = userC.getId();

        // userA 가 그룹 생성, userB 가 초대 수락 → 활성 멤버 2명. userC 는 비멤버.
        GroupCreatedResult group = groupMemberService.createGroup(userAId, "알림 테스트 그룹");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userAId, group.groupId());
        groupMemberService.acceptInviteLink(userBId, invite.token());
        this.groupId = group.groupId();
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        jdbcTemplate.execute("DELETE FROM notification_pins");
        jdbcTemplate.execute("DELETE FROM notifications");
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM invite_links");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        jdbcTemplate.execute("DELETE FROM bot_link_codes");
        jdbcTemplate.execute("DELETE FROM bot_user_mappings");
        userJpaRepository.deleteAll();
    }

    private Pin savePin(Long creatorId, String instagramUrl, String placeName) {
        return pinRepository.save(Pin.autoFromInstagram(
                groupId,
                creatorId,
                new PlaceSearchHit("k-" + instagramUrl, placeName, "A", 37.5, 127.0),
                instagramUrl));
    }

    private Pin savePin(Long creatorId, String instagramUrl) {
        return savePin(creatorId, instagramUrl, "P-" + instagramUrl);
    }

    // ────────────────────────────────────────────────────────────────────
    // 1. AC-1, AC-2, AC-6 — 본인 알림 0건, 상대방 알림 1건 (MANUAL_PIN)
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MANUAL_PIN: 본인 + 상대방 모두 알림 1건씩, 등록자·수신자 필드 정확")
    void createForManualPin_includesSelf_createsForBoth() {
        // given : userA 가 핀 1개 등록
        Pin pin = savePin(userAId, "https://www.instagram.com/p/M1/");

        // when : userA 트리거
        notificationService.createForManualPin(groupId, userAId, pin.getId());

        // then : userA 도 알림 1건(자기 저장 기록), userB 도 알림 1건
        List<Notification> aNotifications =
                notificationJpa.findByReceiverIdOrderByCreatedAtDesc(userAId, org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(aNotifications).hasSize(1);
        Notification selfNotif = aNotifications.get(0);
        assertThat(selfNotif.getType()).isEqualTo(NotificationType.MANUAL_PIN);
        assertThat(selfNotif.getRegisteredBy()).isEqualTo(userAId);
        assertThat(selfNotif.getReceiverId()).isEqualTo(userAId);
        assertThat(selfNotif.getReadAt()).isNull();

        List<Notification> bNotifications =
                notificationJpa.findByReceiverIdOrderByCreatedAtDesc(userBId, org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(bNotifications).hasSize(1);
        Notification partnerNotif = bNotifications.get(0);
        assertThat(partnerNotif.getType()).isEqualTo(NotificationType.MANUAL_PIN);
        assertThat(partnerNotif.getRegisteredBy()).isEqualTo(userAId);
        assertThat(partnerNotif.getReceiverId()).isEqualTo(userBId);
        assertThat(partnerNotif.getReadAt()).isNull();

        // 그리고 NotificationPin 링크 1행 (B 기준)
        List<NotificationPin> links = notificationPinJpa
                .findByNotificationIdOrderBySortOrderAsc(partnerNotif.getId());
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getPinId()).isEqualTo(pin.getId());
        assertThat(links.get(0).getSortOrder()).isZero();
    }

    // ────────────────────────────────────────────────────────────────────
    // 2. AC-2 — 챗봇 N개 핀 → 알림 1건 + NotificationPin N개
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CHATBOT_PINS: N개 핀 묶음 → 상대방 알림 1건 + NotificationPin N행")
    void createForChatbotBatch_multiplePins_singleNotificationWithLinks() {
        // given : userA 가 핀 3개 등록
        Pin p1 = savePin(userAId, "https://www.instagram.com/p/C1/");
        Pin p2 = savePin(userAId, "https://www.instagram.com/p/C2/");
        Pin p3 = savePin(userAId, "https://www.instagram.com/p/C3/");

        // when
        notificationService.createForChatbotBatch(groupId, userAId, List.of(p1.getId(), p2.getId(), p3.getId()));

        // then : userB 는 알림 1건 (type=CHATBOT_PINS) + 링크 3행, sort_order 0/1/2 보존
        List<Notification> bNotifications =
                notificationJpa.findByReceiverIdOrderByCreatedAtDesc(userBId, org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(bNotifications).hasSize(1);
        Notification only = bNotifications.get(0);
        assertThat(only.getType()).isEqualTo(NotificationType.CHATBOT_PINS);

        List<NotificationPin> links = notificationPinJpa
                .findByNotificationIdOrderBySortOrderAsc(only.getId());
        assertThat(links).hasSize(3);
        assertThat(links).extracting(NotificationPin::getPinId)
                .containsExactly(p1.getId(), p2.getId(), p3.getId());
        assertThat(links).extracting(NotificationPin::getSortOrder)
                .containsExactly(0, 1, 2);
    }

    // ────────────────────────────────────────────────────────────────────
    // 3. AC-5, FR-4 / BR-5 — empty pinIds → no-op
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CHATBOT_PINS: pinIds 빈 리스트 → 알림 생성 안 함")
    void createForChatbotBatch_empty_noOp() {
        // when
        notificationService.createForChatbotBatch(groupId, userAId, List.of());

        // then : 모든 사용자에게 알림 0건
        assertThat(notificationJpa.count()).isZero();
        assertThat(notificationPinJpa.count()).isZero();
    }

    // ────────────────────────────────────────────────────────────────────
    // 4. 엣지 7 — 활성 상대방 0명 → no-op
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("그룹에 등록자 본인만 있으면 자신에게만 알림 1건 생성")
    void soloMember_selfNotificationCreated() {
        // given : userC 단독 그룹 (별도 그룹 생성)
        GroupCreatedResult soloGroup = groupMemberService.createGroup(userCId, "솔로 그룹");
        Long soloGroupId = soloGroup.groupId();
        Pin pin = pinRepository.save(Pin.autoFromInstagram(
                soloGroupId,
                userCId,
                new PlaceSearchHit("k-solo", "솔로 장소", "A", 37.5, 127.0),
                "https://www.instagram.com/p/SOLO/"));

        // when
        notificationService.createForManualPin(soloGroupId, userCId, pin.getId());

        // then : userC 에게 자신의 저장 알림 1건 생성 (상대방 없어도 본인 기록은 남음)
        List<Notification> cNotifications = notificationJpa
                .findByReceiverIdOrderByCreatedAtDesc(userCId, org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(cNotifications).hasSize(1);
        assertThat(cNotifications.get(0).getRegisteredBy()).isEqualTo(userCId);
        assertThat(cNotifications.get(0).getReceiverId()).isEqualTo(userCId);
    }

    // ────────────────────────────────────────────────────────────────────
    // 5. AC-12 — markAllRead
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markAllRead: 미읽음 알림이 모두 readAt 설정됨")
    void markAllRead_updatesUnreadOnly() {
        // given : userB 에게 알림 3건 (그중 1건은 이미 읽음 처리)
        Pin p1 = savePin(userAId, "https://www.instagram.com/p/R1/");
        Pin p2 = savePin(userAId, "https://www.instagram.com/p/R2/");
        Pin p3 = savePin(userAId, "https://www.instagram.com/p/R3/");
        notificationService.createForManualPin(groupId, userAId, p1.getId());
        notificationService.createForManualPin(groupId, userAId, p2.getId());
        notificationService.createForManualPin(groupId, userAId, p3.getId());

        // 첫 번째 알림 1건을 미리 읽음 처리 (createdAt DESC 정렬이므로 마지막 등록 = index 0)
        List<Notification> all = notificationJpa.findByReceiverIdOrderByCreatedAtDesc(
                userBId, org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(all).hasSize(3);
        Notification preRead = all.get(0);
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        preRead.markRead(fixed);
        notificationJpa.saveAndFlush(preRead);

        // when
        int updated = notificationService.markAllRead(userBId);

        // then : 2건 갱신, 모든 알림 readAt 채움
        assertThat(updated).isEqualTo(2);
        List<Notification> after = notificationJpa.findByReceiverIdOrderByCreatedAtDesc(
                userBId, org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(after).allMatch(n -> n.getReadAt() != null);

        // 미리 읽음 처리된 알림은 readAt 시각이 변동 없음
        Notification reloaded = notificationJpa.findById(preRead.getId()).orElseThrow();
        assertThat(reloaded.getReadAt()).isEqualTo(fixed);
    }

    // ────────────────────────────────────────────────────────────────────
    // 6. AC-19, BR-4 — 소프트 삭제 핀 detail
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDetail: 소프트 삭제된 핀은 deleted=true + 좌표/주소 null, placeName 유지")
    void getDetail_softDeletedPin_returnsDeletedFlag() {
        // given : 핀 1개 + 알림 1건, 이후 핀 soft delete
        Pin pin = savePin(userAId, "https://www.instagram.com/p/DEL1/", "사라질 장소");
        notificationService.createForManualPin(groupId, userAId, pin.getId());

        pin.delete();
        pinJpaRepository.saveAndFlush(pin);

        Notification target = notificationJpa
                .findByReceiverIdOrderByCreatedAtDesc(userBId, org.springframework.data.domain.PageRequest.of(0, 50))
                .get(0);

        // when
        NotificationService.NotificationDetailResult detail =
                notificationService.getDetail(target.getId(), userBId);

        // then
        assertThat(detail.pins()).hasSize(1);
        NotificationService.NotificationPinItemResult item = detail.pins().get(0);
        assertThat(item.deleted()).isTrue();
        assertThat(item.placeName()).isEqualTo("사라질 장소");
        assertThat(item.address()).isNull();
        assertThat(item.latitude()).isNull();
        assertThat(item.longitude()).isNull();
    }

    // ────────────────────────────────────────────────────────────────────
    // 7. NOT_FOUND — 본인 receiver 가 아닌 알림 조회
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDetail: 본인 receiver 가 아닌 알림 조회 → CoreException NOT_FOUND")
    void getDetail_notReceiverOwn_throwsNotFound() {
        // given : userB 에게 알림 1건
        Pin pin = savePin(userAId, "https://www.instagram.com/p/NF1/");
        notificationService.createForManualPin(groupId, userAId, pin.getId());
        Notification target = notificationJpa
                .findByReceiverIdOrderByCreatedAtDesc(userBId, org.springframework.data.domain.PageRequest.of(0, 50))
                .get(0);

        // when & then : userC(무관 사용자)가 조회 → NOT_FOUND
        assertThatThrownBy(() -> notificationService.getDetail(target.getId(), userCId))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.NOT_FOUND);
    }
}
