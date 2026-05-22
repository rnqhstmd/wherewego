package com.wherewego.interfaces.api.notification;

import com.wherewego.domain.auth.jwt.JwtTokenProvider;
import com.wherewego.domain.notification.Notification;
import com.wherewego.domain.notification.NotificationPin;
import com.wherewego.domain.notification.NotificationRepository;
import com.wherewego.domain.notification.NotificationType;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class NotificationV1ControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    private MockMvc mockMvc;

    private Long userAId;
    private Long userBId;
    private Long userCId;
    private Long groupId;
    private Cookie authCookieA;
    private Cookie authCookieB;
    private Cookie authCookieC;

    @BeforeEach
    void setUp() {
        truncateAll();

        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        UserModel userA = userJpaRepository.save(UserModel.create(80000001L, "userA", null));
        UserModel userB = userJpaRepository.save(UserModel.create(80000002L, "userB", null));
        UserModel userC = userJpaRepository.save(UserModel.create(80000003L, "userC", null));
        this.userAId = userA.getId();
        this.userBId = userB.getId();
        this.userCId = userC.getId();
        this.authCookieA = new Cookie("access_token", jwtTokenProvider.issueAccessToken(userAId));
        this.authCookieB = new Cookie("access_token", jwtTokenProvider.issueAccessToken(userBId));
        this.authCookieC = new Cookie("access_token", jwtTokenProvider.issueAccessToken(userCId));

        // 모든 알림 픽스처가 사용할 그룹을 직접 INSERT (V001 스키마: groups(name), group_members(group_id,user_id))
        this.groupId = jdbcTemplate.queryForObject(
                "INSERT INTO groups (name) VALUES (?) RETURNING id",
                Long.class, "여행팀");
        jdbcTemplate.update(
                "INSERT INTO group_members (group_id, user_id) VALUES (?, ?)",
                groupId, userAId);
        jdbcTemplate.update(
                "INSERT INTO group_members (group_id, user_id) VALUES (?, ?)",
                groupId, userBId);
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

    private Long insertPin(Long groupId, Long createdBy, String placeName) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO pins (group_id, created_by, place_name, address, latitude, longitude, "
                        + "instagram_url, memo, memo_source, tag) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                groupId, createdBy, placeName, "서울 강남구",
                new BigDecimal("37.5000000"), new BigDecimal("127.0000000"),
                null, null, null, "MEMORY");
    }

    private Long saveNotification(Long receiverId, Long registeredBy, NotificationType type, List<Long> pinIds) {
        Notification n = notificationRepository.save(
                Notification.create(groupId, receiverId, registeredBy, type));
        List<NotificationPin> links = new java.util.ArrayList<>(pinIds.size());
        for (int i = 0; i < pinIds.size(); i++) {
            links.add(NotificationPin.link(n.getId(), pinIds.get(i), i));
        }
        notificationRepository.saveAllPins(links);
        return n.getId();
    }

    // ============================================================
    // GET /api/v1/notifications
    // ============================================================

    @DisplayName("GET /api/v1/notifications - 본인 알림을 최신순으로 반환하고 unreadCount 를 포함한다 (AC-11).")
    @Test
    void listNotifications_returnsItemsAndUnreadCount() throws Exception {
        // arrange : userA 가 등록한 핀 3개, userB 수신자로 알림 3건 (2건 미읽음, 1건 읽음)
        Long p1 = insertPin(groupId, userAId, "성수");
        Long p2 = insertPin(groupId, userAId, "한남");
        Long p3 = insertPin(groupId, userAId, "강남");
        Long n1 = saveNotification(userBId, userAId, NotificationType.MANUAL_PIN, List.of(p1));
        saveNotification(userBId, userAId, NotificationType.CHATBOT_PINS, List.of(p2));
        saveNotification(userBId, userAId, NotificationType.MANUAL_PIN, List.of(p3));

        // n1 한 건만 read 처리 → 미읽음 2 건 남음
        jdbcTemplate.update("UPDATE notifications SET read_at = now() WHERE id = ?", n1);

        // act & assert
        mockMvc.perform(get("/api/v1/notifications").cookie(authCookieB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.unreadCount").value(2));
    }

    // ============================================================
    // POST /api/v1/notifications/read-all
    // ============================================================

    @DisplayName("POST /api/v1/notifications/read-all - 본인 미읽음 알림이 모두 읽음 처리되고 updatedCount 가 반환된다 (AC-14).")
    @Test
    void readAll_marksAllUnread() throws Exception {
        // arrange : userB 의 미읽음 알림 3건
        Long p1 = insertPin(groupId, userAId, "성수");
        saveNotification(userBId, userAId, NotificationType.MANUAL_PIN, List.of(p1));
        saveNotification(userBId, userAId, NotificationType.MANUAL_PIN, List.of(p1));
        saveNotification(userBId, userAId, NotificationType.CHATBOT_PINS, List.of(p1));

        // act & assert
        mockMvc.perform(post("/api/v1/notifications/read-all").cookie(authCookieB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.updatedCount").value(3));
    }

    // ============================================================
    // GET /api/v1/notifications/{notificationId}
    // ============================================================

    @DisplayName("GET /api/v1/notifications/{id} - 본인 알림 상세를 핀 목록 + 타입과 함께 반환한다.")
    @Test
    void getDetail_returnsPinsAndType() throws Exception {
        // arrange : userA 가 등록한 핀 2개, userB 수신자 챗봇 알림
        Long p1 = insertPin(groupId, userAId, "성수");
        Long p2 = insertPin(groupId, userAId, "한남");
        Long notificationId = saveNotification(
                userBId, userAId, NotificationType.CHATBOT_PINS, List.of(p1, p2));

        // act & assert
        mockMvc.perform(get("/api/v1/notifications/" + notificationId).cookie(authCookieB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.type").value("CHATBOT_PINS"))
                .andExpect(jsonPath("$.data.pins.length()").value(2));
    }

    @DisplayName("GET /api/v1/notifications/{id} - 수신자 본인이 아닌 알림 조회는 404 를 반환한다.")
    @Test
    void getDetail_notReceiverOwn_returns404() throws Exception {
        // arrange : userB 가 수신자인 알림. userC 로 인증해서 조회 시도
        Long p1 = insertPin(groupId, userAId, "성수");
        Long bsNotificationId = saveNotification(
                userBId, userAId, NotificationType.MANUAL_PIN, List.of(p1));

        // act & assert
        mockMvc.perform(get("/api/v1/notifications/" + bsNotificationId).cookie(authCookieC))
                .andExpect(status().isNotFound());
    }
}
