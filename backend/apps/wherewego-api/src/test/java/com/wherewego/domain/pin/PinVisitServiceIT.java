package com.wherewego.domain.pin;

import com.wherewego.domain.chat.GroupChatMessageFrame;
import com.wherewego.domain.chat.GroupChatService;
import com.wherewego.domain.chat.MessageKind;
import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.InviteLinkIssueResult;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.push.PushNotificationService;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.pin.PinVisitJpaRepository;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 방문 체크인·추억 전환 정책 v2 — 단일 방문 선언 API 통합 테스트(B1, §1-7).
 *
 * <p>클래스에 {@code @Transactional} 을 두지 않아 afterCommit 푸시가 실제 커밋을 일으키도록 한다
 * (GroupChatServiceIT 패턴). 푸시(APNs)는 {@code @MockBean} 으로 대체하여 분기만 검증한다.</p>
 *
 * <p>커버 케이스(설계 §1-7):</p>
 * <ul>
 *     <li>① 체크인(다인 그룹 혼자): 태그 불변 + SELF 적재 + PIN_VISIT 카드(무푸시)</li>
 *     <li>② 동행 전환: MEMORY + 본인 SELF·동행 TAGGED + PIN_MEMORY 카드(푸시) + converted</li>
 *     <li>③ 늦은 제출(이미 MEMORY): 멱등 union + alreadyConverted + 카드 미적재</li>
 *     <li>④ TAGGED → SELF 승격</li>
 *     <li>⑤ 1인 그룹 혼자 = 전환</li>
 *     <li>⑥ 비멤버 동행 400 · 비활성 핀 404</li>
 *     <li>⑦ 푸시 분기(PIN_VISIT 무푸시)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class PinVisitServiceIT {

    @Autowired
    private PinVisitService pinVisitService;

    @Autowired
    private GroupChatService groupChatService;

    @Autowired
    private GroupMemberService groupMemberService;

    @Autowired
    private PinRepository pinRepository;

    @Autowired
    private PinVisitJpaRepository pinVisitJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PushNotificationService pushNotificationService;

    private Long userAId;  // 선언자(self)
    private Long userBId;  // 같은 그룹의 동행
    private Long userCId;  // 같은 그룹의 또 다른 멤버
    private Long groupId;

    @BeforeEach
    void setUp() {
        truncateAll();

        UserModel userA = userJpaRepository.save(UserModel.create(60000001L, "userA", null));
        UserModel userB = userJpaRepository.save(UserModel.create(60000002L, "userB", null));
        UserModel userC = userJpaRepository.save(UserModel.create(60000003L, "userC", null));
        this.userAId = userA.getId();
        this.userBId = userB.getId();
        this.userCId = userC.getId();

        GroupCreatedResult group = groupMemberService.createGroup(userAId, "방문 정책 v2 그룹");
        this.groupId = group.groupId();
        joinGroup(userBId);
        joinGroup(userCId);
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void joinGroup(Long userId) {
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userAId, groupId);
        groupMemberService.acceptInviteLink(userId, invite.token());
    }

    private void truncateAll() {
        jdbcTemplate.execute("DELETE FROM pin_visits");
        jdbcTemplate.execute("DELETE FROM chat_room_reads");
        jdbcTemplate.execute("DELETE FROM chat_message");
        jdbcTemplate.execute("DELETE FROM chat_room");
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM invite_links");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        jdbcTemplate.execute("DELETE FROM devices");
        jdbcTemplate.execute("DELETE FROM bot_link_codes");
        jdbcTemplate.execute("DELETE FROM bot_user_mappings");
        userJpaRepository.deleteAll();
    }

    private Long saveWishPin(String instagramUrl, String placeName) {
        Pin pin = pinRepository.save(Pin.fromSelection(
                groupId, userAId,
                new PlaceSearchHit("k-" + instagramUrl, placeName, "서울", 37.5, 127.0),
                instagramUrl, PinTag.WISH));
        return pin.getId();
    }

    private PinTag tagOf(Long pinId) {
        return pinRepository.findById(pinId).orElseThrow().getTag();
    }

    private List<GroupChatMessageFrame> visitCards(MessageKind kind) {
        return groupChatService.getMessages(userAId, groupId, null, 50).frames().stream()
                .filter(f -> f.kind() == kind)
                .toList();
    }

    // ① 체크인(다인 그룹 혼자)
    @Test
    @DisplayName("체크인 - 다인 그룹 혼자: 태그 불변 + 본인 SELF + PIN_VISIT 카드(무푸시).")
    void checkin_soloInMultiMemberGroup() {
        Long pinId = saveWishPin("https://www.instagram.com/p/C1/", "성수 어니언");

        DeclareVisitResult result = pinVisitService.declareVisit(userAId, groupId, pinId, List.of());

        assertThat(result.converted()).isFalse();
        assertThat(result.alreadyConverted()).isFalse();
        assertThat(tagOf(pinId)).isEqualTo(PinTag.WISH); // 태그 불변
        // 본인 SELF 1행.
        assertThat(pinVisitJpaRepository.findByPinIdAndUserId(pinId, userAId)).isPresent()
                .get().extracting(PinVisit::getSource).isEqualTo(VisitSource.SELF);
        // PIN_VISIT 카드 1개, 발신자 = 방문자.
        List<GroupChatMessageFrame> cards = visitCards(MessageKind.PIN_VISIT);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).senderUserId()).isEqualTo(userAId);
        assertThat(cards.get(0).pinSnapshot()).isNotNull();
        assertThat(cards.get(0).pinSnapshot().placeName()).isEqualTo("성수 어니언");
        // 무푸시(체크인 카드).
        verify(pushNotificationService, never()).pushGroupMessage(anyLong(), anyLong(), eq(MessageKind.PIN_VISIT));
        // 응답 visitors 에 본인 SELF 합류.
        assertThat(result.visitors()).extracting(PinVisitorResult::userId).containsExactly(userAId);
    }

    // ② 동행 전환
    @Test
    @DisplayName("동행 전환 - MEMORY + 본인 SELF·동행 TAGGED + PIN_MEMORY 카드(푸시) + converted.")
    void companion_transition() {
        Long pinId = saveWishPin("https://www.instagram.com/p/C2/", "동행 장소");

        DeclareVisitResult result =
                pinVisitService.declareVisit(userAId, groupId, pinId, List.of(userBId));

        assertThat(result.converted()).isTrue();
        assertThat(result.alreadyConverted()).isFalse();
        assertThat(tagOf(pinId)).isEqualTo(PinTag.MEMORY);
        assertThat(pinVisitJpaRepository.findByPinIdAndUserId(pinId, userAId)).get()
                .extracting(PinVisit::getSource).isEqualTo(VisitSource.SELF);
        assertThat(pinVisitJpaRepository.findByPinIdAndUserId(pinId, userBId)).get()
                .extracting(PinVisit::getSource).isEqualTo(VisitSource.TAGGED);

        // PIN_MEMORY 카드 1개 + visitParticipants(본인 + 동행).
        List<GroupChatMessageFrame> cards = visitCards(MessageKind.PIN_MEMORY);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).visitParticipants())
                .extracting(GroupChatMessageFrame.ChatVisitParticipant::userId)
                .containsExactly(userAId, userBId);
        // 추억 카드 푸시 — 발신자 제외 활성 멤버(B, C)에게 PIN_MEMORY.
        verify(pushNotificationService, timeout(2000))
                .pushGroupMessage(eq(userBId), anyLong(), eq(MessageKind.PIN_MEMORY));
        verify(pushNotificationService, timeout(2000))
                .pushGroupMessage(eq(userCId), anyLong(), eq(MessageKind.PIN_MEMORY));

        assertThat(result.visitors()).extracting(PinVisitorResult::userId)
                .containsExactlyInAnyOrder(userAId, userBId);
    }

    // ③ 늦은 제출(이미 MEMORY): 멱등 union + alreadyConverted + 카드 미적재
    @Test
    @DisplayName("늦은 제출 - 이미 MEMORY: visits union + alreadyConverted + PIN_MEMORY 카드 미적재.")
    void lateSubmission_unionNoCard() {
        Long pinId = saveWishPin("https://www.instagram.com/p/C3/", "선착 장소");
        // 첫 동행 선언(A+B) → MEMORY 전환 + PIN_MEMORY 카드 1개.
        pinVisitService.declareVisit(userAId, groupId, pinId, List.of(userBId));
        assertThat(visitCards(MessageKind.PIN_MEMORY)).hasSize(1);

        // 늦은 제출(B+C): 이미 MEMORY → alreadyConverted, visits union(C 합류), 카드 추가 안 됨.
        DeclareVisitResult late =
                pinVisitService.declareVisit(userBId, groupId, pinId, List.of(userCId));

        assertThat(late.converted()).isFalse();
        assertThat(late.alreadyConverted()).isTrue();
        assertThat(tagOf(pinId)).isEqualTo(PinTag.MEMORY);
        // union: A(SELF) + B(SELF, 본인 선언으로 승격) + C(TAGGED).
        assertThat(pinVisitJpaRepository.findByPinIdAndUserId(pinId, userBId)).get()
                .extracting(PinVisit::getSource).isEqualTo(VisitSource.SELF);
        assertThat(pinVisitJpaRepository.findByPinIdAndUserId(pinId, userCId)).get()
                .extracting(PinVisit::getSource).isEqualTo(VisitSource.TAGGED);
        // 카드 미적재 — 여전히 1개(늦은 제출은 카드 추가 없음, Q3 확정).
        assertThat(visitCards(MessageKind.PIN_MEMORY)).hasSize(1);
        assertThat(late.visitors()).extracting(PinVisitorResult::userId)
                .containsExactlyInAnyOrder(userAId, userBId, userCId);
    }

    // ④ TAGGED → SELF 승격
    @Test
    @DisplayName("TAGGED → SELF 승격 - 동행으로 적재된 멤버가 본인 체크인하면 SELF 승격(중복 행 없음).")
    void taggedPromotesToSelf() {
        Long pinId = saveWishPin("https://www.instagram.com/p/C4/", "승격 장소");
        // A 가 B 를 동행으로 적재 → B 는 TAGGED.
        pinVisitService.declareVisit(userAId, groupId, pinId, List.of(userBId));
        assertThat(pinVisitJpaRepository.findByPinIdAndUserId(pinId, userBId)).get()
                .extracting(PinVisit::getSource).isEqualTo(VisitSource.TAGGED);

        // B 가 본인 체크인(이미 MEMORY 라 alreadyConverted) → TAGGED 행이 SELF 로 승격, 행 수 불변.
        pinVisitService.declareVisit(userBId, groupId, pinId, List.of());

        assertThat(pinVisitJpaRepository.findByPinIdAndUserId(pinId, userBId)).get()
                .extracting(PinVisit::getSource).isEqualTo(VisitSource.SELF);
        long bRows = pinVisitJpaRepository.findByPinIdIn(List.of(pinId)).stream()
                .filter(v -> v.getUserId().equals(userBId)).count();
        assertThat(bRows).isEqualTo(1); // 중복 행 없음(AC-4).
    }

    // ⑤ 1인 그룹 혼자 = 전환
    @Test
    @DisplayName("1인 그룹 혼자 - 'companions 빈'이어도 MEMORY 전환(FR-I6) + PIN_MEMORY 카드.")
    void soloGroup_checkinConverts() {
        GroupCreatedResult solo = groupMemberService.createGroup(userAId, "솔로 그룹");
        Long soloGroupId = solo.groupId();
        Pin pin = pinRepository.save(Pin.fromSelection(
                soloGroupId, userAId,
                new PlaceSearchHit("k-solo", "혼자 장소", "서울", 37.5, 127.0),
                "https://www.instagram.com/p/C5/", PinTag.WISH));

        DeclareVisitResult result =
                pinVisitService.declareVisit(userAId, soloGroupId, pin.getId(), List.of());

        assertThat(result.converted()).isTrue();
        assertThat(pinRepository.findById(pin.getId()).orElseThrow().getTag()).isEqualTo(PinTag.MEMORY);
        // PIN_MEMORY 카드 적재, 1인 그룹이라 푸시 대상(타 멤버)은 없음.
        List<GroupChatMessageFrame> cards = groupChatService.getMessages(userAId, soloGroupId, null, 50)
                .frames().stream().filter(f -> f.kind() == MessageKind.PIN_MEMORY).toList();
        assertThat(cards).hasSize(1);
        verify(pushNotificationService, never()).pushGroupMessage(eq(userAId), anyLong(), eq(MessageKind.PIN_MEMORY));
    }

    // ⑥ 비멤버 동행 400 · 비활성 핀 404
    @Test
    @DisplayName("거부 - 비멤버 동행은 PIN_VISIT_COMPANION_INVALID(400), 비활성 핀은 PIN_NOT_FOUND(404).")
    void rejections() {
        Long pinId = saveWishPin("https://www.instagram.com/p/C6/", "거부 장소");

        // 비멤버(그룹 미가입 user) 동행 → 400.
        UserModel outsider = userJpaRepository.save(UserModel.create(60000099L, "outsider", null));
        assertThatThrownBy(() ->
                pinVisitService.declareVisit(userAId, groupId, pinId, List.of(outsider.getId())))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.PIN_VISIT_COMPANION_INVALID);

        // 비활성 핀(soft-delete) → 404.
        jdbcTemplate.update("UPDATE pins SET deleted_at = now() WHERE id = ?", pinId);
        assertThatThrownBy(() ->
                pinVisitService.declareVisit(userAId, groupId, pinId, List.of()))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.PIN_NOT_FOUND);
    }

    // ⑦ 푸시 분기 — 체크인 카드(PIN_VISIT)는 무푸시.
    @Test
    @DisplayName("푸시 분기 - 체크인(PIN_VISIT)은 어떤 멤버에게도 푸시하지 않는다.")
    void checkin_noPushAtAll() {
        Long pinId = saveWishPin("https://www.instagram.com/p/C7/", "무푸시 장소");

        pinVisitService.declareVisit(userAId, groupId, pinId, List.of());

        verify(pushNotificationService, never())
                .pushGroupMessage(anyLong(), anyLong(), eq(MessageKind.PIN_VISIT));
        verify(pushNotificationService, never())
                .pushGroupMessage(eq(userBId), anyLong(), eq(MessageKind.PIN_MEMORY));
        verify(pushNotificationService, never())
                .pushGroupMessage(eq(userCId), anyLong(), eq(MessageKind.PIN_MEMORY));
    }
}
