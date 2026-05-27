package com.wherewego.domain.pin.want;

import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.InviteLinkIssueResult;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinEventAction;
import com.wherewego.domain.pin.PinEventRepository;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.user.UserModel;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 12 {@link WantService#toggle} 통합 테스트 (AC-12-5, 7, 8~10).
 *
 * <p>2인 그룹 픽스처 패턴은 {@code PinServiceIT}/{@code NotificationServiceVisitDetectedIT} 답습:
 * create → invite → accept 로 활성 멤버 2명을 만들고 클래스에 {@code @Transactional} 을 두지 않아
 * WantService 의 자체 트랜잭션이 실제 커밋되도록 한다.</p>
 *
 * <p>커버 시나리오:</p>
 * <ul>
 *     <li>(1) 기본 토글 — A 가 REEL 핀에 WANT → wantCount=1 / myWant=true / tag=REEL 유지 (과반 미달)</li>
 *     <li>(2) 과반 충족 + WISH 전환 — B 도 WANT → wantCount=2 / tag=WISH / wishConverted=true (AC-12-8, 9)</li>
 *     <li>(3) WISH 전환 후 취소 — A 가 WANT 취소 → wantCount=1 / tag=WISH 유지 (역전환 없음, AC-12-10)</li>
 *     <li>(4) MEMORY 핀 거부 — MEMORY 핀에 WANT → PIN_WANT_FORBIDDEN_TAG (AC-12-6 백엔드 가드)</li>
 *     <li>(5) 비활성(비멤버) — 비멤버는 GROUP_NOT_MEMBER 403</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class WantToggleIT {

    @Autowired
    private WantService wantService;

    @Autowired
    private PinRepository pinRepository;

    @Autowired
    private PinJpaRepository pinJpaRepository;

    @Autowired
    private PinEventRepository pinEventRepository;

    @Autowired
    private GroupMemberService groupMemberService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userAId;
    private Long userBId;
    private Long userCId;
    private Long groupId;

    @BeforeEach
    void setUp() {
        truncateAll();

        UserModel userA = userJpaRepository.save(UserModel.create(70000001L, "userA", null));
        UserModel userB = userJpaRepository.save(UserModel.create(70000002L, "userB", null));
        UserModel userC = userJpaRepository.save(UserModel.create(70000003L, "userC", null));
        this.userAId = userA.getId();
        this.userBId = userB.getId();
        this.userCId = userC.getId();

        // userA 그룹 + userB 수락 → 활성 멤버 2명. userC 는 비멤버.
        GroupCreatedResult group = groupMemberService.createGroup(userAId, "WANT 테스트 그룹");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userAId, group.groupId());
        groupMemberService.acceptInviteLink(userBId, invite.token());
        this.groupId = group.groupId();
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        // Phase 12 V012: pin_events 는 pins 보다 먼저 (FK 또는 의존성 단순화).
        jdbcTemplate.execute("DELETE FROM pin_events");
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

    private Pin saveReelPin(Long creatorId, String instagramUrl) {
        return pinRepository.save(Pin.autoFromInstagram(
                groupId, creatorId,
                new PlaceSearchHit("k-" + instagramUrl, "P-" + instagramUrl, "A", 37.5, 127.0),
                instagramUrl));
    }

    private Pin saveMemoryPin(Long creatorId, String instagramUrl) {
        Pin pin = Pin.autoFromInstagram(
                groupId, creatorId,
                new PlaceSearchHit("k-" + instagramUrl, "P-" + instagramUrl, "A", 37.5, 127.0),
                instagramUrl);
        pin.changeTag(PinTag.MEMORY);
        return pinJpaRepository.saveAndFlush(pin);
    }

    @DisplayName("toggle - 2인 그룹 A 가 REEL 핀에 WANT → wantCount=1, myWant=true, tag=REEL 유지 (과반 미달).")
    @Test
    void toggle_singleVote_keepsReel() {
        // arrange
        Pin pin = saveReelPin(userAId, "https://www.instagram.com/p/W1/");

        // act
        WantToggleResult result = wantService.toggle(userAId, groupId, pin.getId());

        // assert
        assertThat(result.wantCount()).isEqualTo(1);
        assertThat(result.myWant()).isTrue();
        assertThat(result.tag()).isEqualTo(PinTag.REEL);
        assertThat(result.wishConverted()).isFalse();

        // pin_events 1건 INSERT
        assertThat(pinEventRepository.existsByPinAndUserAndAction(pin.getId(), userAId, PinEventAction.WANT))
                .isTrue();
        assertThat(pinEventRepository.countWantByPinId(pin.getId())).isEqualTo(1);

        // DB 영속 확인
        Pin reloaded = pinJpaRepository.findById(pin.getId()).orElseThrow();
        assertThat(reloaded.getWantCount()).isEqualTo(1);
        assertThat(reloaded.getTag()).isEqualTo(PinTag.REEL);
    }

    @DisplayName("toggle - A→B 순서로 WANT → wantCount=2, tag=WISH, wishConverted=true (AC-12-8, 9).")
    @Test
    void toggle_majorityReached_transitionsToWish() {
        // arrange : REEL 핀 생성 후 A 가 먼저 WANT
        Pin pin = saveReelPin(userAId, "https://www.instagram.com/p/W2/");
        wantService.toggle(userAId, groupId, pin.getId());

        // act : B 가 두 번째 WANT → 2/2 과반 (floor(2/2)+1 = 2)
        WantToggleResult result = wantService.toggle(userBId, groupId, pin.getId());

        // assert
        assertThat(result.wantCount()).isEqualTo(2);
        assertThat(result.myWant()).isTrue();
        assertThat(result.tag()).isEqualTo(PinTag.WISH);
        assertThat(result.wishConverted()).isTrue();

        // pin_events 2건
        assertThat(pinEventRepository.countWantByPinId(pin.getId())).isEqualTo(2);

        // DB 영속 확인
        Pin reloaded = pinJpaRepository.findById(pin.getId()).orElseThrow();
        assertThat(reloaded.getWantCount()).isEqualTo(2);
        assertThat(reloaded.getTag()).isEqualTo(PinTag.WISH);
    }

    @DisplayName("toggle - WISH 전환 후 A 가 WANT 취소 → wantCount=1, tag=WISH 유지 (역전환 없음, AC-12-10).")
    @Test
    void toggle_cancelAfterWishConverted_keepsWishTag() {
        // arrange : A→B WANT 로 WISH 전환 완료
        Pin pin = saveReelPin(userAId, "https://www.instagram.com/p/W3/");
        wantService.toggle(userAId, groupId, pin.getId());
        wantService.toggle(userBId, groupId, pin.getId());
        Pin afterConvert = pinJpaRepository.findById(pin.getId()).orElseThrow();
        assertThat(afterConvert.getTag()).isEqualTo(PinTag.WISH);

        // act : A 가 WANT 취소 (DELETE)
        WantToggleResult result = wantService.toggle(userAId, groupId, pin.getId());

        // assert : count 만 감소, tag 는 WISH 유지 (역전환 정책)
        assertThat(result.wantCount()).isEqualTo(1);
        assertThat(result.myWant()).isFalse();
        assertThat(result.tag()).isEqualTo(PinTag.WISH);
        assertThat(result.wishConverted()).isFalse();

        // pin_events : A 의 WANT 행 제거됨, B 의 행만 남음
        assertThat(pinEventRepository.existsByPinAndUserAndAction(pin.getId(), userAId, PinEventAction.WANT))
                .isFalse();
        assertThat(pinEventRepository.existsByPinAndUserAndAction(pin.getId(), userBId, PinEventAction.WANT))
                .isTrue();
        assertThat(pinEventRepository.countWantByPinId(pin.getId())).isEqualTo(1);
    }

    @DisplayName("toggle - MEMORY 핀에 WANT 호출 시 PIN_WANT_FORBIDDEN_TAG 를 반환한다.")
    @Test
    void toggle_onMemoryPin_throwsForbiddenTag() {
        // arrange : MEMORY 태그 핀
        Pin pin = saveMemoryPin(userAId, "https://www.instagram.com/p/W4/");

        // act & assert
        assertThatThrownBy(() -> wantService.toggle(userAId, groupId, pin.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_WANT_FORBIDDEN_TAG);

        // pin_events 미생성 / want_count 미증가
        assertThat(pinEventRepository.countWantByPinId(pin.getId())).isZero();
        Pin reloaded = pinJpaRepository.findById(pin.getId()).orElseThrow();
        assertThat(reloaded.getWantCount()).isZero();
    }

    @DisplayName("toggle - 비활성(비멤버) 사용자는 GROUP_NOT_MEMBER 를 반환한다.")
    @Test
    void toggle_nonMember_throwsGroupNotMember() {
        // arrange
        Pin pin = saveReelPin(userAId, "https://www.instagram.com/p/W5/");

        // act & assert : userC 는 비멤버
        assertThatThrownBy(() -> wantService.toggle(userCId, groupId, pin.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.GROUP_NOT_MEMBER);

        // 부수효과 없음
        assertThat(pinEventRepository.countWantByPinId(pin.getId())).isZero();
    }
}
