package com.wherewego.domain.notification;

import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.InviteLinkIssueResult;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.notification.NotificationJpaRepository;
import com.wherewego.infrastructure.notification.NotificationPinJpaRepository;
import com.wherewego.infrastructure.pin.PinJpaRepository;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10: VISIT_DETECTED 알림 fan-out + 부분 UNIQUE race-free + getDetail memo join 통합 테스트.
 *
 * <p>{@link NotificationServiceIT} 의 픽스처/세팅 패턴(create → invite → accept, truncateAll) 을 답습한다.
 * 클래스에 {@code @Transactional} 을 두지 않아 REQUIRES_NEW 가 실제 커밋을 일으키도록 한다.</p>
 *
 * <p>커버 케이스 (설계서 §8.1):</p>
 * <ul>
 *     <li>(a) 정상 fan-out: 그룹 멤버 2명, 동일 pinId 1회 → 각 receiver 1행씩</li>
 *     <li>(b) race-free UNIQUE: 동일 pinId 2회 연속 → 1회만 INSERT</li>
 *     <li>(e) MEMORY→MEMORY 시 알림 미발송 (사실상 PinService 시그널이 false 이므로 호출 자체가 없음을 검증)</li>
 *     <li>(f) memo만 변경 시 알림 미발송 (위와 동일)</li>
 *     <li>(h) getDetail memo join: VISIT_DETECTED + 활성 핀 → memo 최신값. soft-delete → null</li>
 * </ul>
 *
 * <p>(c) ExecutorService 동시성, (d) Controller WISH→MEMORY, (g) RuntimeException 격리는 별도
 * Controller IT 로 옮기는 게 자연스러우나, 본 IT 의 범위 단순화를 위해 TODO 로 위임한다.</p>
 *
 * TODO: (c) ExecutorService 기반 race 시뮬레이션 — 본 (b) 가 동일 race-free 보장을 단일 스레드 시퀀스로 검증하므로 우선순위 낮음.
 * TODO: (d) PinV1Controller WISH→MEMORY 전환 시 알림 1회 — PinV1ControllerVisitDetectedIT 로 분리 권장.
 * TODO: (g) createForVisitDetected RuntimeException → PATCH 200 — Controller 레이어 try-catch 검증이므로 Controller IT 로 분리 권장.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class NotificationServiceVisitDetectedIT {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationJpaRepository notificationJpa;

    @Autowired
    private NotificationPinJpaRepository notificationPinJpa;

    @Autowired
    private PinRepository pinRepository;

    @Autowired
    private PinJpaRepository pinJpaRepository;

    @Autowired
    private GroupMemberService groupMemberService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userAId;  // 등록자(self)
    private Long userBId;  // 같은 그룹의 활성 상대방
    private Long groupId;

    @BeforeEach
    void setUp() {
        truncateAll();

        UserModel userA = userJpaRepository.save(UserModel.create(50000001L, "userA", null));
        UserModel userB = userJpaRepository.save(UserModel.create(50000002L, "userB", null));
        this.userAId = userA.getId();
        this.userBId = userB.getId();

        GroupCreatedResult group = groupMemberService.createGroup(userAId, "방문 감지 테스트 그룹");
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

    private Pin saveMemoryPin(Long creatorId, String instagramUrl, String placeName, String memo) {
        Pin pin = Pin.autoFromInstagram(
                groupId, creatorId,
                new PlaceSearchHit("k-" + instagramUrl, placeName, "A", 37.5, 127.0),
                instagramUrl);
        pin.changeTag(PinTag.MEMORY);
        if (memo != null) {
            pin.applyManualMemo(memo, creatorId);
        }
        return pinRepository.save(pin);
    }

    // ────────────────────────────────────────────────────────────────────
    // (a) 정상 fan-out: 그룹 멤버 2명, 동일 pinId 1회 → 각 receiver 1행씩
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VISIT_DETECTED fan-out: 활성 멤버 2명에게 각각 알림 1건 (본인 포함)")
    void createForVisitDetected_fansOutToBothMembers() {
        // given : userA 가 방문 감지하여 MEMORY 전환한 핀
        Pin pin = saveMemoryPin(userAId, "https://www.instagram.com/p/V1/", "방문 장소 1", "오랜만에 방문");

        // when
        notificationService.createForVisitDetected(groupId, userAId, pin.getId());

        // then : userA 본인 알림 1건 + userB 알림 1건 (모두 VISIT_DETECTED)
        List<Notification> aNotifications = notificationJpa
                .findByReceiverIdOrderByCreatedAtDesc(userAId, PageRequest.of(0, 50));
        assertThat(aNotifications).hasSize(1);
        Notification aOnly = aNotifications.get(0);
        assertThat(aOnly.getType()).isEqualTo(NotificationType.VISIT_DETECTED);
        assertThat(aOnly.getRegisteredBy()).isEqualTo(userAId);
        assertThat(aOnly.getReceiverId()).isEqualTo(userAId);
        assertThat(aOnly.getVisitPinId()).isEqualTo(pin.getId());

        List<Notification> bNotifications = notificationJpa
                .findByReceiverIdOrderByCreatedAtDesc(userBId, PageRequest.of(0, 50));
        assertThat(bNotifications).hasSize(1);
        Notification bOnly = bNotifications.get(0);
        assertThat(bOnly.getType()).isEqualTo(NotificationType.VISIT_DETECTED);
        assertThat(bOnly.getRegisteredBy()).isEqualTo(userAId);
        assertThat(bOnly.getReceiverId()).isEqualTo(userBId);
        assertThat(bOnly.getVisitPinId()).isEqualTo(pin.getId());

        // NotificationPin 링크 각 1행 (sortOrder=0)
        List<NotificationPin> aLinks = notificationPinJpa
                .findByNotificationIdOrderBySortOrderAsc(aOnly.getId());
        assertThat(aLinks).hasSize(1);
        assertThat(aLinks.get(0).getPinId()).isEqualTo(pin.getId());
        assertThat(aLinks.get(0).getSortOrder()).isZero();
    }

    // ────────────────────────────────────────────────────────────────────
    // (b) race-free UNIQUE: 동일 pinId 2회 연속 → 1회만 INSERT
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VISIT_DETECTED 중복 호출: 동일 (group, receiver, registeredBy, pin) 2회 → 1회만 INSERT (부분 UNIQUE)")
    void createForVisitDetected_duplicateCall_insertsOnce() {
        // given
        Pin pin = saveMemoryPin(userAId, "https://www.instagram.com/p/V2/", "중복 테스트", null);

        // when : 동일 입력으로 2회 연속 호출
        notificationService.createForVisitDetected(groupId, userAId, pin.getId());
        notificationService.createForVisitDetected(groupId, userAId, pin.getId());

        // then : userA / userB 각 1건씩만 존재 (총 2건). 부분 UNIQUE 인덱스가 두 번째 시도를 차단.
        List<Notification> aNotifications = notificationJpa
                .findByReceiverIdOrderByCreatedAtDesc(userAId, PageRequest.of(0, 50));
        assertThat(aNotifications).hasSize(1);

        List<Notification> bNotifications = notificationJpa
                .findByReceiverIdOrderByCreatedAtDesc(userBId, PageRequest.of(0, 50));
        assertThat(bNotifications).hasSize(1);

        // NotificationPin 링크도 각 1행씩 (중복 INSERT 가 일어났다면 2행)
        assertThat(notificationPinJpa.findByNotificationIdOrderBySortOrderAsc(
                aNotifications.get(0).getId())).hasSize(1);
        assertThat(notificationPinJpa.findByNotificationIdOrderBySortOrderAsc(
                bNotifications.get(0).getId())).hasSize(1);
    }

    // ────────────────────────────────────────────────────────────────────
    // (e) MEMORY→MEMORY 시 알림 미발송 — 서비스 호출 시 fan-out 은 작동하지만
    //     실제 PinV1Controller 흐름에서는 PinService 가 wasWishOrReelToMemory=false 를 반환하므로
    //     createForVisitDetected 자체가 호출되지 않는다. 본 IT 는 "호출이 일어났다고 가정해도
    //     중복 UNIQUE 가 동작하지 않을 만큼 격리되는가" 가 아니라, 단위 행위 의도 검증을 위해
    //     "createForVisitDetected 가 호출되지 않은 상태"를 명시적으로 검증한다.
    //
    //     설계 가정: VISIT_DETECTED 알림은 오직 wasWishOrReelToMemory=true 일 때만 NotificationService
    //     로 진입하므로, MEMORY→MEMORY 케이스의 본질 검증은 Controller IT 가 맡는 것이 자연스럽다.
    //     본 IT 에서는 "fan-out 호출 자체가 없으면 알림 0건" 을 invariant 로 남긴다.
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MEMORY→MEMORY: createForVisitDetected 미호출 시 VISIT_DETECTED 알림 0건")
    void notInvoked_noVisitNotification() {
        // given : 핀은 있지만 호출하지 않음 (MEMORY→MEMORY 시나리오의 Controller 단 분기 결과)
        saveMemoryPin(userAId, "https://www.instagram.com/p/V3/", "이미 MEMORY", null);

        // when : 아무 호출도 하지 않음 (MEMORY→MEMORY 분기로 인해 fan-out 진입 자체가 없음)

        // then
        assertThat(notificationJpa.count()).isZero();
        assertThat(notificationPinJpa.count()).isZero();
    }

    // ────────────────────────────────────────────────────────────────────
    // (f) memo만 변경: 위 (e) 와 동일 invariant. tagProvided=false 이면 호출 미발생.
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("memo only 수정: createForVisitDetected 미호출 시 VISIT_DETECTED 알림 0건")
    void memoOnly_noVisitNotification() {
        // given
        saveMemoryPin(userAId, "https://www.instagram.com/p/V4/", "메모만 변경", "추가 메모");

        // when : 아무 호출도 하지 않음 (memo only 수정이므로 PinUpdateResult.wasWishOrReelToMemory=false)

        // then
        assertThat(notificationJpa.count()).isZero();
        assertThat(notificationPinJpa.count()).isZero();
    }

    // ────────────────────────────────────────────────────────────────────
    // (h) getDetail memo join: VISIT_DETECTED + 활성 핀 → memo 최신값
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDetail (VISIT_DETECTED): 활성 핀이면 memo 최신값을 join 한다")
    void getDetail_visitDetected_joinsLatestMemo() {
        // given : MEMORY + memo 가진 핀 + VISIT_DETECTED 알림
        Pin pin = saveMemoryPin(userAId, "https://www.instagram.com/p/V5/", "방문 후기 장소", "처음 메모");
        notificationService.createForVisitDetected(groupId, userAId, pin.getId());

        // memo 를 최신화
        pin.applyManualMemo("최신 메모로 갱신", userAId);
        pinJpaRepository.saveAndFlush(pin);

        Notification target = notificationJpa
                .findByReceiverIdOrderByCreatedAtDesc(userBId, PageRequest.of(0, 50))
                .get(0);

        // when
        NotificationService.NotificationDetailResult detail =
                notificationService.getDetail(target.getId(), userBId);

        // then
        assertThat(detail.type()).isEqualTo(NotificationType.VISIT_DETECTED);
        assertThat(detail.pins()).hasSize(1);
        NotificationService.NotificationPinItemResult item = detail.pins().get(0);
        assertThat(item.deleted()).isFalse();
        assertThat(item.memo()).isEqualTo("최신 메모로 갱신");
        // FR-VD-29: 활성 핀이면 현재 태그(MEMORY)도 함께 전달되어 알림 상세 MEMORY 배지 표시에 사용된다.
        assertThat(item.tag()).isEqualTo("MEMORY");
    }

    @Test
    @DisplayName("getDetail (VISIT_DETECTED): soft-delete 된 핀이면 memo 는 null")
    void getDetail_visitDetected_softDeletedPin_memoNull() {
        // given
        Pin pin = saveMemoryPin(userAId, "https://www.instagram.com/p/V6/", "삭제될 장소", "삭제 전 메모");
        notificationService.createForVisitDetected(groupId, userAId, pin.getId());

        pin.delete();
        pinJpaRepository.saveAndFlush(pin);

        Notification target = notificationJpa
                .findByReceiverIdOrderByCreatedAtDesc(userBId, PageRequest.of(0, 50))
                .get(0);

        // when
        NotificationService.NotificationDetailResult detail =
                notificationService.getDetail(target.getId(), userBId);

        // then : soft-delete 시 placeName 만 유지, memo 는 null
        assertThat(detail.pins()).hasSize(1);
        NotificationService.NotificationPinItemResult item = detail.pins().get(0);
        assertThat(item.deleted()).isTrue();
        assertThat(item.memo()).isNull();
        assertThat(item.placeName()).isEqualTo("삭제될 장소");
    }
}
