package com.wherewego.domain.chat;

import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.InviteLinkIssueResult;
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
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * GM-2 (B단계): 그룹별 봇 방 서비스 통합 테스트(FR-1~FR-7, AC-2~AC-9).
 *
 * <p>그룹별 봇 방 격리, DM 목록(봇 방 있는 그룹 + 가상 항목 혼합), unread 전이, 멤버십 403을
 * 실제 PostgreSQL Testcontainer + 실제 트랜잭션 위에서 검증한다. {@link UserDeletionServiceMultiGroupIT}/
 * {@link com.wherewego.domain.notification.NotificationServiceIT} 의 그룹/멤버 픽스처 패턴을 따른다.</p>
 *
 * <p>봇 1턴 비동기 처리({@link BotChatProcessor})는 외부 호출(스크래핑/Gemini/장소검색)을 유발하므로
 * {@code @MockBean} 으로 대체한다 — 본 테스트는 방 구조/DM/읽음만 검증하며 1턴 처리는 범위 밖이다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class BotChatServiceGroupIT {

    @Autowired
    private BotChatService botChatService;

    @Autowired
    private GroupMemberService groupMemberService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private BotChatProcessor botChatProcessor;

    private Long userA;
    private Long userB;

    @BeforeEach
    void setUp() {
        truncateAll();
        doNothing().when(botChatProcessor).processAsync(anyLong(), anyLong(), anyString());
        userA = userJpaRepository.save(UserModel.create(40000001L, "userA", null)).getId();
        userB = userJpaRepository.save(UserModel.create(40000002L, "userB", null)).getId();
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        jdbcTemplate.execute("DELETE FROM chat_message");
        jdbcTemplate.execute("DELETE FROM chat_room");
        jdbcTemplate.execute("DELETE FROM invite_links");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        jdbcTemplate.execute("DELETE FROM devices");
        jdbcTemplate.execute("DELETE FROM bot_link_codes");
        jdbcTemplate.execute("DELETE FROM bot_user_mappings");
        userJpaRepository.deleteAll();
    }

    @DisplayName("postMessage - 같은 사용자가 두 그룹에 보내면 그룹별로 별개 봇 방이 생성되고 메시지가 섞이지 않는다.")
    @Test
    void postMessage_perGroupRoomsAreIsolated() {
        Long group1 = groupMemberService.createGroup(userA, "그룹1").groupId();
        Long group2 = groupMemberService.createGroup(userA, "그룹2").groupId();

        botChatService.postMessage(userA, group1, "https://instagram.com/reel/aaa");
        botChatService.postMessage(userA, group2, "https://instagram.com/reel/bbb");

        // 그룹별로 활성 봇 방 1개씩 생성됨(owner+group 부분 UNIQUE).
        assertThat(activeBotRoomCount(userA, group1)).isEqualTo(1);
        assertThat(activeBotRoomCount(userA, group2)).isEqualTo(1);

        // 각 방 메시지는 USER/TEXT + BOT/PROCESSING 2건씩, 그룹 간 격리.
        ChatMessagePageResult page1 = botChatService.getBotMessages(userA, group1, null, 20);
        ChatMessagePageResult page2 = botChatService.getBotMessages(userA, group2, null, 20);
        assertThat(page1.messages()).hasSize(2);
        assertThat(page2.messages()).hasSize(2);
        assertThat(page1.messages()).noneMatch(m -> page2.messages().contains(m));
    }

    @DisplayName("postMessage - 같은 그룹에 두 번 보내도 봇 방은 재사용된다(활성 1개 유지).")
    @Test
    void postMessage_reusesActiveRoom() {
        Long group1 = groupMemberService.createGroup(userA, "그룹1").groupId();

        botChatService.postMessage(userA, group1, "https://instagram.com/reel/aaa");
        botChatService.postMessage(userA, group1, "https://instagram.com/reel/bbb");

        assertThat(activeBotRoomCount(userA, group1)).isEqualTo(1);
        assertThat(botChatService.getBotMessages(userA, group1, null, 20).messages()).hasSize(4);
    }

    @DisplayName("getBotRooms - 봇 방이 있는 그룹과 없는 그룹(가상 항목)이 활성 그룹 전부로 표시된다.")
    @Test
    void getBotRooms_includesEmptyGroupsAsVirtual() {
        Long group1 = groupMemberService.createGroup(userA, "그룹1").groupId();
        Long group2 = groupMemberService.createGroup(userA, "그룹2").groupId();

        // group1 에만 메시지 전송 → 봇 방 생성. group2 는 대화 없음(가상 항목).
        botChatService.postMessage(userA, group1, "https://instagram.com/reel/aaa");

        List<BotRoomSummary> rooms = botChatService.getBotRooms(userA);
        assertThat(rooms).hasSize(2);

        BotRoomSummary r1 = rooms.stream().filter(r -> r.groupId().equals(group1)).findFirst().orElseThrow();
        BotRoomSummary r2 = rooms.stream().filter(r -> r.groupId().equals(group2)).findFirst().orElseThrow();

        // group1: 실제 방 — roomId·preview 존재.
        assertThat(r1.roomId()).isNotNull();
        assertThat(r1.groupName()).isEqualTo("그룹1");
        assertThat(r1.lastPreview()).isNotNull();

        // group2: 가상 항목 — roomId/preview/lastSenderType/lastAt=null, unread=false(AC-7).
        assertThat(r2.roomId()).isNull();
        assertThat(r2.groupName()).isEqualTo("그룹2");
        assertThat(r2.lastPreview()).isNull();
        assertThat(r2.lastSenderType()).isNull();
        assertThat(r2.lastAt()).isNull();
        assertThat(r2.unread()).isFalse();
    }

    @DisplayName("unread 전이 - 마지막이 봇 메시지면 unread=true, 방 조회 후 false, 마지막이 USER 면 false.")
    @Test
    void unread_transitions() {
        Long group1 = groupMemberService.createGroup(userA, "그룹1").groupId();
        botChatService.postMessage(userA, group1, "https://instagram.com/reel/aaa");
        Long roomId = singleActiveBotRoomId(userA, group1);

        // 봇 결과 메시지를 직접 append(비동기 processor 를 mock 했으므로 수동 삽입).
        insertBotPlaceCards(roomId);

        // 마지막이 BOT → unread=true (아직 조회 전).
        assertThat(unreadOf(group1)).isTrue();

        // 방 조회 → 읽음 처리(last_read 갱신).
        botChatService.getBotMessages(userA, group1, null, 20);
        assertThat(unreadOf(group1)).isFalse();

        // 사용자가 다시 메시지 전송 → 마지막이 USER 인 PROCESSING(BOT) 이 추가됨.
        //   PROCESSING 도 BOT 이지만, 사용자 입장에서 "내가 보낸 직후"라 unread 규칙상 BOT 최신이면 true.
        //   여기서는 '마지막이 USER 면 false' 규칙 검증을 위해 USER 단독 마지막 상태를 직접 만든다.
        insertUserText(roomId, userA);
        assertThat(unreadOf(group1)).isFalse();
    }

    @DisplayName("postMessage - 비멤버 그룹 봇 방 전송은 GROUP_NOT_MEMBER(403).")
    @Test
    void postMessage_nonMember_forbidden() {
        Long group1 = groupMemberService.createGroup(userA, "그룹1").groupId();

        assertThatThrownBy(() -> botChatService.postMessage(userB, group1, "https://instagram.com/reel/x"))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
    }

    @DisplayName("getBotMessages - 비멤버 그룹 봇 방 조회는 GROUP_NOT_MEMBER(403).")
    @Test
    void getBotMessages_nonMember_forbidden() {
        Long group1 = groupMemberService.createGroup(userA, "그룹1").groupId();

        assertThatThrownBy(() -> botChatService.getBotMessages(userB, group1, null, 20))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).getErrorType())
                .isEqualTo(ErrorType.GROUP_NOT_MEMBER);
    }

    @DisplayName("하위호환 postMessage(userId, text) - 최신 활성 그룹 봇 방으로 폴백 전송된다.")
    @Test
    @SuppressWarnings("deprecation")
    void legacyPostMessage_fallsBackToLatestActiveGroup() {
        groupMemberService.createGroup(userA, "그룹1");
        Long latest = groupMemberService.createGroup(userA, "그룹2").groupId();

        botChatService.postMessage(userA, "https://instagram.com/reel/aaa");

        // 최신 활성 그룹(group2)에 봇 방 + 메시지 생성.
        assertThat(activeBotRoomCount(userA, latest)).isEqualTo(1);
    }

    // --- 헬퍼: 픽스처는 GroupMemberService 로, 검증/직접삽입은 jdbcTemplate 로 ---

    private Integer activeBotRoomCount(Long ownerUserId, Long groupId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room "
                        + "WHERE owner_user_id = ? AND group_id = ? AND type = 'BOT' AND deleted_at IS NULL",
                Integer.class, ownerUserId, groupId);
    }

    private Long singleActiveBotRoomId(Long ownerUserId, Long groupId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM chat_room "
                        + "WHERE owner_user_id = ? AND group_id = ? AND type = 'BOT' AND deleted_at IS NULL",
                Long.class, ownerUserId, groupId);
    }

    private boolean unreadOf(Long groupId) {
        return botChatService.getBotRooms(userA).stream()
                .filter(r -> r.groupId().equals(groupId))
                .findFirst().orElseThrow()
                .unread();
    }

    private void insertBotPlaceCards(Long roomId) {
        jdbcTemplate.update(
                "INSERT INTO chat_message (room_id, sender_type, sender_user_id, kind, payload_json) "
                        + "VALUES (?, 'BOT', NULL, 'PLACE_CARDS', '{\"cards\":[{\"name\":\"A\"}]}'::jsonb)",
                roomId);
    }

    private void insertUserText(Long roomId, Long userId) {
        jdbcTemplate.update(
                "INSERT INTO chat_message (room_id, sender_type, sender_user_id, kind, payload_json) "
                        + "VALUES (?, 'USER', ?, 'TEXT', '{\"text\":\"hi\"}'::jsonb)",
                roomId, userId);
    }
}
