package com.wherewego.domain.chat;

import com.wherewego.domain.chat.BotPlaceCardsPayloadBuilder.PlaceCardsPayload;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.InviteLinkIssueResult;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.place.ReelPlaceExtractor;
import com.wherewego.domain.push.PushNotificationService;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.domain.user.UserModel;
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
import static org.mockito.Mockito.when;

/**
 * GC-1: 그룹 채팅 서비스 통합 테스트(FR-GC1-1~8, AC-1~AC-8).
 *
 * <p>그룹 방 자동 생성/get-or-create, kind 분기 전송 검증, REEL_LINK registered 파생(핀 저장→true,
 * 전부 삭제→false 회귀, 동일 URL 메시지 동시 전이), 멤버별 읽음 독립, 추출 권한(발신자만·탈퇴 NULL 거부),
 * 푸시 fan-out(1인 그룹 생략)을 실제 PostgreSQL Testcontainer 위에서 검증한다.
 * {@link BotChatServiceGroupIT} 의 픽스처 패턴을 따른다.</p>
 *
 * <p>외부 호출 컴포넌트는 {@code @MockBean} 으로 대체한다 — {@link ReelPlaceExtractor}(스크래핑/Gemini/검색),
 * {@link PushNotificationService}(APNs).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class GroupChatServiceIT {

    private static final String REEL_URL = "https://instagram.com/reel/aaa111";
    private static final String REEL_URL_2 = "https://instagram.com/reel/bbb222";

    @Autowired
    private GroupChatService groupChatService;

    @Autowired
    private GroupMemberService groupMemberService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private PinRepository pinRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ReelPlaceExtractor reelPlaceExtractor;

    @MockBean
    private PushNotificationService pushNotificationService;

    // GC-3: 실제 IG 스크래핑(외부 네트워크)이 afterCommit 비동기로 일어나지 않도록 mock 으로 대체한다.
    // 썸네일 파생/노출은 payload 를 직접 심거나 writer 단위로 검증한다(외부 호출 무의존).
    @MockBean
    private ReelThumbnailService reelThumbnailService;

    @Autowired
    private ReelThumbnailWriter reelThumbnailWriter;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    private Long userA;
    private Long userB;

    @BeforeEach
    void setUp() {
        truncateAll();
        userA = userJpaRepository.save(UserModel.create(50000001L, "userA", null)).getId();
        userB = userJpaRepository.save(UserModel.create(50000002L, "userB", null)).getId();
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
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

    // ────── FR-GC1-1: 방 자동 생성 + 멤버십 강제 ──────

    @DisplayName("createGroup - 그룹 생성 시 활성 GROUP 방이 자동 생성된다(FR-GC1-1).")
    @Test
    void createGroup_autoCreatesGroupRoom() {
        Long groupId = groupMemberService.createGroup(userA, "그룹1").groupId();

        assertThat(activeGroupRoomCount(groupId)).isEqualTo(1);
    }

    @DisplayName("postMessage - 활성 방이 없으면 get-or-create 안전망이 ON CONFLICT insert 로 방을 생성한다(PR #118 리뷰 반영 경로).")
    @Test
    void postMessage_recreatesRoomWhenAbsent() {
        Long groupId = groupMemberService.createGroup(userA, "그룹1").groupId();
        // 자동 생성된 방을 soft delete 하여 '방 없음' 상태를 만든다 → insertGroupRoomIfAbsent 경로 실행.
        jdbcTemplate.update(
                "UPDATE chat_room SET deleted_at = now() WHERE group_id = ? AND type = 'GROUP'", groupId);

        groupChatService.postMessage(userA, groupId, MessageKind.TEXT, "재생성", null, null);

        assertThat(activeGroupRoomCount(groupId)).isEqualTo(1);
        assertThat(groupChatService.getMessages(userA, groupId, null, 20).frames()).hasSize(1);
    }

    @DisplayName("postMessage - TEXT 전송 후 타 멤버가 조회로 수신한다. 발신자/닉네임이 프레임에 합성된다(AC-1).")
    @Test
    void postMessage_text_visibleToOtherMember() {
        Long groupId = createSharedGroup();

        groupChatService.postMessage(userA, groupId, MessageKind.TEXT, "주말에 성수?", null, null);

        GroupMessagesPage page = groupChatService.getMessages(userB, groupId, null, 20);
        assertThat(page.frames()).hasSize(1);
        GroupChatMessageFrame frame = page.frames().get(0);
        assertThat(frame.kind()).isEqualTo(MessageKind.TEXT);
        assertThat(frame.senderUserId()).isEqualTo(userA);
        assertThat(frame.senderNickname()).isEqualTo("userA");
        assertThat(frame.registered()).isNull();
    }

    @DisplayName("GP-1 FR-6: 프레임에 발신자 유효 프사 URL 이 합성된다 — 프사 없는 발신자는 null, 발신자 NULL(탈퇴)도 null(BR-6).")
    @Test
    void postMessage_frameCarriesSenderProfileImageUrl() {
        Long groupId = createSharedGroup();
        Long messageId = groupChatService.postMessage(userA, groupId, MessageKind.TEXT, "안녕", null, null).getId();

        // userA 는 프사 키/카카오 URL 모두 없음(create(..., null)) → senderProfileImageUrl null, 닉네임은 존재.
        GroupChatMessageFrame frame = frameOf(groupId, messageId);
        assertThat(frame.senderUserId()).isEqualTo(userA);
        assertThat(frame.senderNickname()).isEqualTo("userA");
        assertThat(frame.senderProfileImageUrl()).isNull();

        // 발신자 탈퇴(sender_user_id NULL) → 닉네임/프사 모두 null(클라 "(알 수 없음)" 이니셜).
        jdbcTemplate.update("UPDATE chat_message SET sender_user_id = NULL WHERE id = ?", messageId);
        GroupChatMessageFrame anon = frameOf(groupId, messageId);
        assertThat(anon.senderUserId()).isNull();
        assertThat(anon.senderNickname()).isNull();
        assertThat(anon.senderProfileImageUrl()).isNull();
    }

    @DisplayName("postMessage/getMessages - 비멤버는 GROUP_NOT_MEMBER(403).")
    @Test
    void nonMember_forbidden() {
        Long groupId = groupMemberService.createGroup(userA, "그룹1").groupId();

        assertThatThrownBy(() -> groupChatService.postMessage(userB, groupId, MessageKind.TEXT, "hi", null, null))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
        assertThatThrownBy(() -> groupChatService.getMessages(userB, groupId, null, 20))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
    }

    // ────── FR-GC1-3: kind 분기 검증 ──────

    @DisplayName("postMessage - TEXT 빈 본문/2000자 초과는 CHAT_TEXT_INVALID(400).")
    @Test
    void postMessage_textValidation() {
        Long groupId = groupMemberService.createGroup(userA, "그룹1").groupId();

        assertErrorType(() -> groupChatService.postMessage(userA, groupId, MessageKind.TEXT, "  ", null, null),
                ErrorType.CHAT_TEXT_INVALID);
        assertErrorType(() -> groupChatService.postMessage(userA, groupId, MessageKind.TEXT, "a".repeat(2001), null, null),
                ErrorType.CHAT_TEXT_INVALID);
    }

    @DisplayName("postMessage - REEL_LINK 는 https + 인스타 패턴만 허용(CHAT_REEL_URL_INVALID 400).")
    @Test
    void postMessage_reelUrlValidation() {
        Long groupId = groupMemberService.createGroup(userA, "그룹1").groupId();

        assertErrorType(() -> groupChatService.postMessage(userA, groupId, MessageKind.REEL_LINK, null,
                        "http://instagram.com/reel/aaa", null),
                ErrorType.CHAT_REEL_URL_INVALID);
        assertErrorType(() -> groupChatService.postMessage(userA, groupId, MessageKind.REEL_LINK, null,
                        "https://example.com/reel/aaa", null),
                ErrorType.CHAT_REEL_URL_INVALID);
        assertErrorType(() -> groupChatService.postMessage(userA, groupId, MessageKind.REEL_LINK, null, null, null),
                ErrorType.CHAT_REEL_URL_INVALID);
        // 2000자 상한(payload 비대 차단) — 패턴은 통과하지만 길이로 거부되는 케이스.
        assertErrorType(() -> groupChatService.postMessage(userA, groupId, MessageKind.REEL_LINK, null,
                        "https://instagram.com/reel/aaa?" + "x".repeat(2000), null),
                ErrorType.CHAT_REEL_URL_INVALID);
    }

    @DisplayName("postMessage - TEXT/REEL_LINK 외 kind 는 CHAT_KIND_INVALID(400).")
    @Test
    void postMessage_kindValidation() {
        Long groupId = groupMemberService.createGroup(userA, "그룹1").groupId();

        assertErrorType(() -> groupChatService.postMessage(userA, groupId, MessageKind.PROCESSING, "x", null, null),
                ErrorType.CHAT_KIND_INVALID);
        assertErrorType(() -> groupChatService.postMessage(userA, groupId, null, "x", null, null),
                ErrorType.CHAT_KIND_INVALID);
    }

    // ────── FR-GC1-4: registered 파생 ──────

    @DisplayName("registered 파생 - 전송 직후 false → 핀 저장 후 true(동일 URL 두 메시지 모두) → 핀 전부 삭제 시 false 회귀(AC-2~4).")
    @Test
    void registered_derivesFromPins() {
        Long groupId = createSharedGroup();
        groupChatService.postMessage(userA, groupId, MessageKind.REEL_LINK, null, REEL_URL, null);
        groupChatService.postMessage(userB, groupId, MessageKind.REEL_LINK, null, REEL_URL, null);

        // 전송 직후: 핀 없음 → 두 메시지 모두 registered=false.
        assertThat(registeredFlags(groupId)).containsExactly(false, false);

        // 해당 URL 로 핀 저장(기존 핀 API 경로와 동일하게 pins 에 instagram_url 기록).
        Long pinId = savePin(groupId, userA, REEL_URL);
        assertThat(registeredFlags(groupId)).containsExactly(true, true);

        // 그 릴스 핀 전부 삭제 → false 회귀(재등록 가능 — 수용된 트레이드오프).
        jdbcTemplate.update("UPDATE pins SET deleted_at = now() WHERE id = ?", pinId);
        assertThat(registeredFlags(groupId)).containsExactly(false, false);
    }

    // ────── PIN_REPLY: 핀 답장 전송 + pinSnapshot 파생 ──────

    @DisplayName("PIN_REPLY - 전송 성공 후 조회 시 pinSnapshot(placeName·tag·deleted=false)이 합성된다.")
    @Test
    void pinReply_sendAndSnapshot() {
        Long groupId = createSharedGroup();
        Long pinId = savePin(groupId, userA, REEL_URL); // placeName="성수 어니언", tag=WISH

        Long messageId = groupChatService.postMessage(
                userA, groupId, MessageKind.PIN_REPLY, "여기 가보고 싶어!", null, pinId).getId();

        GroupChatMessageFrame frame = frameOf(groupId, messageId);
        assertThat(frame.kind()).isEqualTo(MessageKind.PIN_REPLY);
        assertThat(frame.senderUserId()).isEqualTo(userA);
        // PIN_REPLY 아닌 파생 필드는 null.
        assertThat(frame.registered()).isNull();
        assertThat(frame.thumbnailUrl()).isNull();

        GroupChatMessageFrame.PinSnapshot snapshot = frame.pinSnapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.pinId()).isEqualTo(pinId);
        assertThat(snapshot.placeName()).isEqualTo("성수 어니언");
        assertThat(snapshot.tag()).isEqualTo("WISH");
        assertThat(snapshot.deleted()).isFalse();
    }

    @DisplayName("PIN_REPLY - 타 그룹 핀/없는 핀/null pinId 는 CHAT_PIN_INVALID(400).")
    @Test
    void pinReply_pinValidation() {
        Long groupId = createSharedGroup();
        Long otherGroup = groupMemberService.createGroup(userA, "타그룹").groupId();
        Long otherGroupPin = savePin(otherGroup, userA, REEL_URL); // 다른 그룹의 핀

        // 타 그룹 핀 → 400.
        assertErrorType(() -> groupChatService.postMessage(
                        userA, groupId, MessageKind.PIN_REPLY, "hi", null, otherGroupPin),
                ErrorType.CHAT_PIN_INVALID);
        // 없는 핀 → 400.
        assertErrorType(() -> groupChatService.postMessage(
                        userA, groupId, MessageKind.PIN_REPLY, "hi", null, 999_999L),
                ErrorType.CHAT_PIN_INVALID);
        // pinId 누락 → 400.
        assertErrorType(() -> groupChatService.postMessage(
                        userA, groupId, MessageKind.PIN_REPLY, "hi", null, null),
                ErrorType.CHAT_PIN_INVALID);

        // 삭제된 핀 → 400(소속 그룹이어도 비활성).
        Long pinId = savePin(groupId, userA, REEL_URL_2);
        jdbcTemplate.update("UPDATE pins SET deleted_at = now() WHERE id = ?", pinId);
        assertErrorType(() -> groupChatService.postMessage(
                        userA, groupId, MessageKind.PIN_REPLY, "hi", null, pinId),
                ErrorType.CHAT_PIN_INVALID);
    }

    @DisplayName("PIN_REPLY - 전송 후 핀 soft-delete → 재조회 시 pinSnapshot.deleted=true(placeName 유지·자기치유).")
    @Test
    void pinReply_snapshotSelfHealsOnDelete() {
        Long groupId = createSharedGroup();
        Long pinId = savePin(groupId, userA, REEL_URL);
        Long messageId = groupChatService.postMessage(
                userA, groupId, MessageKind.PIN_REPLY, "여기 좋아", null, pinId).getId();

        // 전송 직후: deleted=false.
        assertThat(frameOf(groupId, messageId).pinSnapshot().deleted()).isFalse();

        // 핀 soft-delete → 재조회 시 deleted=true + placeName 유지 + 사진/메모 null(자기치유).
        jdbcTemplate.update("UPDATE pins SET deleted_at = now() WHERE id = ?", pinId);
        GroupChatMessageFrame.PinSnapshot healed = frameOf(groupId, messageId).pinSnapshot();
        assertThat(healed.deleted()).isTrue();
        assertThat(healed.placeName()).isEqualTo("성수 어니언");
        assertThat(healed.tag()).isNull();
        assertThat(healed.memo()).isNull();
        assertThat(healed.photoThumbnailUrl()).isNull();
        assertThat(healed.photoUrl()).isNull();
    }

    // ────── FR-GC3-2: 릴스 썸네일 파생/노출 ──────

    @DisplayName("thumbnailUrl - payload 에 thumbnailUrl 이 있으면 REEL_LINK 프레임 top-level thumbnailUrl 로 노출되고, 비-REEL_LINK 는 null(AC1/AC5).")
    @Test
    void thumbnailUrl_derivedForReelLink_nullOtherwise() {
        Long groupId = createSharedGroup();
        Long reelId = groupChatService.postMessage(userA, groupId, MessageKind.REEL_LINK, null, REEL_URL, null).getId();
        groupChatService.postMessage(userA, groupId, MessageKind.TEXT, "hi", null, null);

        // 전송 직후: 비동기 스크래핑(mock) 미반영 → thumbnailUrl null.
        assertThat(thumbnailOf(groupId, reelId)).isNull();

        // 스크래핑 성공을 모사하여 payload 에 thumbnailUrl 을 심는다(외부 호출 무의존, writer 경로 재사용).
        String thumb = "https://scontent.cdninstagram.com/v/thumb.jpg";
        reelThumbnailWriter.attach(reelId, thumb);

        GroupMessagesPage page = groupChatService.getMessages(userA, groupId, null, 20);
        // REEL_LINK 프레임은 thumbnailUrl 채워지고, TEXT 프레임은 null.
        assertThat(thumbnailOf(groupId, reelId)).isEqualTo(thumb);
        for (GroupChatMessageFrame frame : page.frames()) {
            if (frame.kind() != MessageKind.REEL_LINK) {
                assertThat(frame.thumbnailUrl()).isNull();
            }
        }
    }

    @DisplayName("attach - 비-REEL_LINK/없는 메시지는 no-op, 기존 url 은 보존된다(M5/방어).")
    @Test
    void attach_noOpForNonReelOrMissing() {
        Long groupId = createSharedGroup();
        Long textId = groupChatService.postMessage(userA, groupId, MessageKind.TEXT, "hi", null, null).getId();

        // 비-REEL_LINK → no-op(예외 없음).
        reelThumbnailWriter.attach(textId, "https://x/thumb.jpg");
        // 없는 메시지 → no-op.
        reelThumbnailWriter.attach(999_999L, "https://x/thumb.jpg");

        // REEL_LINK attach 는 url 을 보존한 채 thumbnailUrl 만 채운다.
        Long reelId = groupChatService.postMessage(userA, groupId, MessageKind.REEL_LINK, null, REEL_URL, null).getId();
        reelThumbnailWriter.attach(reelId, "https://x/thumb.jpg");

        ChatMessage reload = chatMessageRepository.findActiveById(reelId).orElseThrow();
        assertThat(reload.getPayloadJson()).contains(REEL_URL);
        assertThat(reload.getPayloadJson()).contains("https://x/thumb.jpg");
    }

    // ────── FR-GC1-5/6: 온디맨드 추출 + 권한 ──────

    @DisplayName("extractPlaces - 발신자가 호출하면 카드 목록을 동기 반환하고 메시지를 append 하지 않는다(AC-5).")
    @Test
    void extractPlaces_senderGetsCards() {
        Long groupId = createSharedGroup();
        Long messageId = groupChatService.postMessage(
                userA, groupId, MessageKind.REEL_LINK, null, REEL_URL, null).getId();
        when(reelPlaceExtractor.extract(eq(REEL_URL), anyLong())).thenReturn(List.of(
                new PlaceSearchHit("11111111", "성수 어니언", "서울 성동구", 37.5443, 127.0557)));

        PlaceCardsPayload payload = groupChatService.extractPlaces(userA, groupId, messageId);

        assertThat(payload.sourceInstagramUrl()).isEqualTo(REEL_URL);
        assertThat(payload.cards()).hasSize(1);
        assertThat(payload.cards().get(0).name()).isEqualTo("성수 어니언");
        // 추출은 read-only — 채팅 메시지가 추가되지 않는다.
        assertThat(groupChatService.getMessages(userA, groupId, null, 20).frames()).hasSize(1);
    }

    @DisplayName("extractPlaces - 타 멤버/탈퇴 발신자(NULL)/REEL_LINK 아님/없는 메시지는 각각 403·403·400·404.")
    @Test
    void extractPlaces_validationChain() {
        Long groupId = createSharedGroup();
        Long reelId = groupChatService.postMessage(
                userA, groupId, MessageKind.REEL_LINK, null, REEL_URL, null).getId();
        Long textId = groupChatService.postMessage(
                userA, groupId, MessageKind.TEXT, "hi", null, null).getId();

        // 타 멤버 호출 → 403.
        assertErrorType(() -> groupChatService.extractPlaces(userB, groupId, reelId),
                ErrorType.CHAT_EXTRACT_FORBIDDEN);
        // REEL_LINK 아님 → 400.
        assertErrorType(() -> groupChatService.extractPlaces(userA, groupId, textId),
                ErrorType.CHAT_NOT_REEL_LINK);
        // 없는 메시지 → 404.
        assertErrorType(() -> groupChatService.extractPlaces(userA, groupId, 999_999L),
                ErrorType.CHAT_MESSAGE_NOT_FOUND);
        // 발신자 탈퇴(sender_user_id NULL) → 영구 등록전(403, MVP 확정 정책).
        jdbcTemplate.update("UPDATE chat_message SET sender_user_id = NULL WHERE id = ?", reelId);
        assertErrorType(() -> groupChatService.extractPlaces(userA, groupId, reelId),
                ErrorType.CHAT_EXTRACT_FORBIDDEN);
    }

    // ────── FR-GC1-2/7: 멤버별 읽음 + 방 목록 ──────

    @DisplayName("멤버별 읽음 독립 - A 전송 시 B 만 unread, B 조회 후 B 해제, A 는 내내 false(AC-6).")
    @Test
    void unread_isPerMember() {
        Long groupId = createSharedGroup();
        groupChatService.postMessage(userA, groupId, MessageKind.TEXT, "안녕", null, null);

        assertThat(unreadOf(userA, groupId)).isFalse(); // 내가 보낸 마지막 → false
        assertThat(unreadOf(userB, groupId)).isTrue();

        groupChatService.getMessages(userB, groupId, null, 20); // B 조회 → 읽음 전진
        assertThat(unreadOf(userB, groupId)).isFalse();
        assertThat(unreadOf(userA, groupId)).isFalse(); // A 포인터는 독립 — 영향 없음

        groupChatService.postMessage(userB, groupId, MessageKind.TEXT, "ㅎㅇ", null, null);
        assertThat(unreadOf(userA, groupId)).isTrue(); // 이제 A 가 미읽음
        assertThat(unreadOf(userB, groupId)).isFalse();
    }

    @DisplayName("getRooms - 전 활성 그룹 노출 + preview kind 규칙(TEXT=본문, REEL_LINK=「릴스 링크」)(AC-7).")
    @Test
    void getRooms_previewRules() {
        Long group1 = groupMemberService.createGroup(userA, "그룹1").groupId();
        Long group2 = groupMemberService.createGroup(userA, "그룹2").groupId();
        groupChatService.postMessage(userA, group1, MessageKind.TEXT, "주말에 성수?", null, null);
        groupChatService.postMessage(userA, group2, MessageKind.REEL_LINK, null, REEL_URL, null);

        List<GroupRoomSummary> rooms = groupChatService.getRooms(userA);
        assertThat(rooms).hasSize(2);

        GroupRoomSummary r1 = roomOf(rooms, group1);
        GroupRoomSummary r2 = roomOf(rooms, group2);
        assertThat(r1.roomId()).isNotNull(); // 자동 생성으로 가상 항목이 아닌 실제 방
        assertThat(r1.lastPreview()).isEqualTo("주말에 성수?");
        assertThat(r1.lastSenderUserId()).isEqualTo(userA);
        assertThat(r2.lastPreview()).isEqualTo("릴스 링크");
    }

    // ────── FR-GC1-8: 푸시 ──────

    @DisplayName("푸시 - 2인 그룹은 발신자 제외 멤버에게 kind 별 푸시, 1인 그룹은 생략(AC-8).")
    @Test
    void push_fanOutExceptSender() {
        Long shared = createSharedGroup();
        Long solo = groupMemberService.createGroup(userA, "솔로").groupId();

        groupChatService.postMessage(userA, shared, MessageKind.REEL_LINK, null, REEL_URL_2, null);
        // afterCommit 비동기 아님(동기 콜백)이지만 커밋 직후 실행 — timeout 으로 안전 대기.
        verify(pushNotificationService, timeout(2000))
                .pushGroupMessage(eq(userB), anyLong(), eq(MessageKind.REEL_LINK));

        groupChatService.postMessage(userA, solo, MessageKind.TEXT, "혼자", null, null);
        verify(pushNotificationService, never())
                .pushGroupMessage(eq(userA), anyLong(), eq(MessageKind.TEXT));
    }

    // ────── 정책 v2: 방문 카드 프레임 조립(PIN_VISIT/PIN_MEMORY) ──────

    @DisplayName("appendVisitCard - PIN_VISIT/PIN_MEMORY 프레임에 pinSnapshot 이, PIN_MEMORY 에 visitParticipants 가 합성된다.")
    @Test
    void visitCard_framesCarrySnapshotAndParticipants() {
        Long groupId = createSharedGroup();
        Long pinId = savePin(groupId, userA, REEL_URL); // placeName="성수 어니언", tag=WISH

        // PIN_VISIT(체크인 카드): pinSnapshot 합성 + visitParticipants 는 null.
        groupChatService.appendVisitCard(groupId, userA, MessageKind.PIN_VISIT, pinId, List.of());
        // PIN_MEMORY(추억 카드): pinSnapshot + payload.userIds → visitParticipants 합성.
        groupChatService.appendVisitCard(groupId, userA, MessageKind.PIN_MEMORY, pinId, List.of(userA, userB));

        List<GroupChatMessageFrame> frames = groupChatService.getMessages(userA, groupId, null, 20).frames();

        GroupChatMessageFrame visit = frames.stream()
                .filter(f -> f.kind() == MessageKind.PIN_VISIT).findFirst().orElseThrow();
        assertThat(visit.senderUserId()).isEqualTo(userA);
        assertThat(visit.pinSnapshot()).isNotNull();
        assertThat(visit.pinSnapshot().placeName()).isEqualTo("성수 어니언");
        assertThat(visit.visitParticipants()).isNull();

        GroupChatMessageFrame memory = frames.stream()
                .filter(f -> f.kind() == MessageKind.PIN_MEMORY).findFirst().orElseThrow();
        assertThat(memory.pinSnapshot()).isNotNull();
        assertThat(memory.pinSnapshot().placeName()).isEqualTo("성수 어니언");
        assertThat(memory.visitParticipants())
                .extracting(GroupChatMessageFrame.ChatVisitParticipant::userId)
                .containsExactly(userA, userB);
    }

    // ────── 헬퍼 ──────

    /** userA 생성 + 초대 링크로 userB 합류한 2인 그룹. */
    private Long createSharedGroup() {
        Long groupId = groupMemberService.createGroup(userA, "공유그룹").groupId();
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userA, groupId);
        groupMemberService.acceptInviteLink(userB, invite.token());
        return groupId;
    }

    private Long savePin(Long groupId, Long ownerUserId, String instagramUrl) {
        Pin pin = pinRepository.save(Pin.fromSelection(groupId, ownerUserId,
                new PlaceSearchHit("11111111", "성수 어니언", "서울 성동구", 37.5443, 127.0557),
                instagramUrl, PinTag.WISH));
        return pin.getId();
    }

    /** 페이지 내 REEL_LINK 프레임의 registered 플래그(최신순). */
    private List<Boolean> registeredFlags(Long groupId) {
        return groupChatService.getMessages(userA, groupId, null, 20).frames().stream()
                .filter(f -> f.kind() == MessageKind.REEL_LINK)
                .map(GroupChatMessageFrame::registered)
                .toList();
    }

    /** 특정 메시지 id 프레임의 top-level thumbnailUrl(없으면 null). */
    private String thumbnailOf(Long groupId, Long messageId) {
        return groupChatService.getMessages(userA, groupId, null, 20).frames().stream()
                .filter(f -> f.messageId().equals(messageId))
                .map(GroupChatMessageFrame::thumbnailUrl)
                .findFirst()
                .orElse(null);
    }

    /** 특정 메시지 id 프레임(없으면 예외). userA 조회 기준. */
    private GroupChatMessageFrame frameOf(Long groupId, Long messageId) {
        return groupChatService.getMessages(userA, groupId, null, 20).frames().stream()
                .filter(f -> f.messageId().equals(messageId))
                .findFirst()
                .orElseThrow();
    }

    private boolean unreadOf(Long userId, Long groupId) {
        return roomOf(groupChatService.getRooms(userId), groupId).hasUnread();
    }

    private static GroupRoomSummary roomOf(List<GroupRoomSummary> rooms, Long groupId) {
        return rooms.stream().filter(r -> r.groupId().equals(groupId)).findFirst().orElseThrow();
    }

    private Integer activeGroupRoomCount(Long groupId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room WHERE group_id = ? AND type = 'GROUP' AND deleted_at IS NULL",
                Integer.class, groupId);
    }

    private static void assertErrorType(Runnable call, ErrorType expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(expected);
    }
}
