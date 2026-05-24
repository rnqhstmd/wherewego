package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.user.UserModel;
import com.wherewego.infrastructure.user.UserJpaRepository;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pin 저장소 통합 테스트. V001 {@code uq_pins_group_instagram} UNIQUE 제약을 실제 INSERT 로 검증.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class PinRepositoryIT {

    @Autowired
    private PinRepository pinRepository;

    @Autowired
    private PinJpaRepository pinJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long groupId;
    private Long groupId2;

    @BeforeEach
    void cleanUp() {
        truncateAll();

        UserModel user = userJpaRepository.save(UserModel.create(987654321L, "pin-tester", null));
        this.userId = user.getId();
        this.groupId = jdbcTemplate.queryForObject(
                "INSERT INTO groups (name) VALUES (?) RETURNING id",
                Long.class, "group-A"
        );
        this.groupId2 = jdbcTemplate.queryForObject(
                "INSERT INTO groups (name) VALUES (?) RETURNING id",
                Long.class, "group-B"
        );
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        // pins → group_members → groups → bot_* → users 순서로 정리 (FK 의존성).
        // 다른 IT 와 격리 보장 위해 users 참조 자식 테이블을 모두 비운다.
        // Phase 10 V009: notifications.visit_pin_id → pins(id) FK 추가로 인해 pins 보다 먼저 삭제 필요.
        jdbcTemplate.execute("DELETE FROM notification_pins");
        jdbcTemplate.execute("DELETE FROM notifications");
        jdbcTemplate.execute("DELETE FROM pins");
        jdbcTemplate.execute("DELETE FROM group_members");
        jdbcTemplate.execute("DELETE FROM groups");
        jdbcTemplate.execute("DELETE FROM bot_link_codes");
        jdbcTemplate.execute("DELETE FROM bot_user_mappings");
        userJpaRepository.deleteAll();
    }

    @DisplayName("save - 동일 (group_id, instagram_url) 두 번째 INSERT 는 DataIntegrityViolationException 으로 거부된다 (AC-13).")
    @Test
    void save_duplicateGroupAndInstagramUrl_throwsDataIntegrityViolation() {
        // arrange
        PlaceSearchHit hit = new PlaceSearchHit("kakao-1", "장소A", "주소A", 37.5, 127.0);
        String instagramUrl = "https://www.instagram.com/p/ABCDE/";
        pinRepository.save(Pin.autoFromInstagram(groupId, userId, hit, instagramUrl));

        // act & assert
        PlaceSearchHit hit2 = new PlaceSearchHit("kakao-2", "장소A-다른표기", "주소A", 37.5, 127.0);
        assertThatThrownBy(() ->
                // JPA의 flush 시점에 제약 위반이 표면화되므로 직접 save+flush 처리
                pinJpaRepository.saveAndFlush(Pin.autoFromInstagram(groupId, userId, hit2, instagramUrl))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("save - 다른 그룹이면 동일 instagram_url 이라도 정상 저장된다 (UNIQUE 범위가 group_id 포함).")
    @Test
    void save_sameInstagramUrlDifferentGroup_succeeds() {
        // arrange
        PlaceSearchHit hit = new PlaceSearchHit("kakao-1", "장소A", "주소A", 37.5, 127.0);
        String instagramUrl = "https://www.instagram.com/p/ZZZZZ/";
        pinRepository.save(Pin.autoFromInstagram(groupId, userId, hit, instagramUrl));

        // act
        Pin second = pinRepository.save(Pin.autoFromInstagram(groupId2, userId, hit, instagramUrl));

        // assert
        assertThat(second.getId()).isNotNull();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pins WHERE instagram_url = ?",
                Integer.class, instagramUrl
        );
        assertThat(count).isEqualTo(2);
    }

    @DisplayName("findActiveByGroupIdOrderByCreatedAtDesc - deleted_at IS NULL 인 핀만 created_at 내림차순으로 반환한다 (AC-1).")
    @Test
    void findActiveByGroupIdOrderByCreatedAtDesc_returnsOnlyActiveDesc() throws InterruptedException {
        // arrange : 시간 격차를 두고 3개 핀 생성, 그 중 1개는 삭제
        Pin first = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-1", "P1", "A1", 37.5, 127.0),
                        "https://www.instagram.com/p/AAA/"));
        Thread.sleep(10);
        Pin second = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-2", "P2", "A2", 37.5, 127.0),
                        "https://www.instagram.com/p/BBB/"));
        Thread.sleep(10);
        Pin third = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-3", "P3", "A3", 37.5, 127.0),
                        "https://www.instagram.com/p/CCC/"));

        // second 만 soft delete
        second.delete();
        pinJpaRepository.saveAndFlush(second);

        // act
        List<Pin> result = pinRepository.findActiveByGroupIdOrderByCreatedAtDesc(groupId);

        // assert : 2개, 최신 등록 순 (third → first)
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Pin::getId).containsExactly(third.getId(), first.getId());
    }

    @DisplayName("findActiveByGroupIdAndTagOrderByCreatedAtDesc - tag 필터를 적용해 반환한다 (AC-2).")
    @Test
    void findActiveByGroupIdAndTagOrderByCreatedAtDesc_filtersByTag() {
        // arrange : REEL 2 개, MEMORY 1 개
        Pin reel1 = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-1", "P1", "A1", 37.5, 127.0),
                        "https://www.instagram.com/p/D1/"));
        Pin reel2 = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-2", "P2", "A2", 37.5, 127.0),
                        "https://www.instagram.com/p/D2/"));
        Pin memory1 = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-3", "P3", "A3", 37.5, 127.0),
                        "https://www.instagram.com/p/D3/"));
        memory1.changeTag(PinTag.MEMORY);
        pinJpaRepository.saveAndFlush(memory1);

        // act
        List<Pin> reelOnly = pinRepository.findActiveByGroupIdAndTagOrderByCreatedAtDesc(
                groupId, PinTag.REEL);

        // assert : REEL 2 개
        assertThat(reelOnly).hasSize(2);
        assertThat(reelOnly).extracting(Pin::getId).containsExactlyInAnyOrder(reel1.getId(), reel2.getId());
        assertThat(reelOnly).allSatisfy(p ->
                assertThat(p.getTag()).isEqualTo(PinTag.REEL));
    }

    @DisplayName("findActiveByIdAndGroupIdForUpdate - 이미 deleted_at IS NOT NULL 인 핀은 빈 결과를 반환한다 (AC-14, AC-17).")
    @Test
    @Transactional
    void findActiveByIdAndGroupIdForUpdate_deletedPin_returnsEmpty() {
        // arrange : 핀 생성 후 soft delete
        Pin pin = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-1", "P1", "A1", 37.5, 127.0),
                        "https://www.instagram.com/p/DEL/"));
        pin.delete();
        pinJpaRepository.saveAndFlush(pin);

        // act
        var result = pinRepository.findActiveByIdAndGroupIdForUpdate(pin.getId(), groupId);

        // assert
        assertThat(result).isEmpty();
    }

    @DisplayName("findActiveByIdAndGroupIdForUpdate - 다른 그룹의 핀은 빈 결과를 반환한다 (BR-1).")
    @Test
    @Transactional
    void findActiveByIdAndGroupIdForUpdate_otherGroup_returnsEmpty() {
        // arrange : groupId 에 핀 생성, groupId2 로 조회
        Pin pin = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-1", "P1", "A1", 37.5, 127.0),
                        "https://www.instagram.com/p/OG/"));

        // act
        var result = pinRepository.findActiveByIdAndGroupIdForUpdate(pin.getId(), groupId2);

        // assert
        assertThat(result).isEmpty();
    }

    @DisplayName("updateAutoMemoIfNotManual - applyManualMemo 후 호출하면 0 행을 갱신한다 (AC-10).")
    @Test
    @Transactional
    void updateAutoMemoIfNotManual_afterManualMemo_returnsZero() {
        // arrange : 핀 생성 후 수동 메모 적용
        Pin pin = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-1", "P1", "A1", 37.5, 127.0),
                        "https://www.instagram.com/p/MN/"));
        pin.applyManualMemo("수동 메모", userId);
        pinJpaRepository.saveAndFlush(pin);

        // act
        int updated = pinJpaRepository.updateAutoMemoIfNotManual(pin.getId(), userId, "auto-memo");

        // assert : MANUAL 보호로 0 행
        assertThat(updated).isEqualTo(0);
    }

    @DisplayName("updateAutoMemoIfNotManual - clearMemo 후 호출하면 1 행을 갱신한다 (AC-11).")
    @Test
    @Transactional
    void updateAutoMemoIfNotManual_afterClearMemo_returnsOne() {
        // arrange : 핀 생성 → 수동 메모 → 잠금 해제(clearMemo)
        Pin pin = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-1", "P1", "A1", 37.5, 127.0),
                        "https://www.instagram.com/p/CL/"));
        pin.applyManualMemo("수동 메모", userId);
        pinJpaRepository.saveAndFlush(pin);
        pin.clearMemo();
        pinJpaRepository.saveAndFlush(pin);

        // act
        int updated = pinJpaRepository.updateAutoMemoIfNotManual(pin.getId(), userId, "auto-memo");

        // assert : memoSource = NULL 이므로 WHERE 통과 → 1 행
        assertThat(updated).isEqualTo(1);
    }

    @DisplayName("updateAutoMemoIfNotManual - 소프트 삭제된 핀에 호출하면 0 행을 갱신한다 (cross-review GAP 회귀).")
    @Test
    @Transactional
    void updateAutoMemoIfNotManual_onDeletedPin_returnsZero() {
        // arrange : 핀 생성 → 소프트 삭제
        Pin pin = pinJpaRepository.saveAndFlush(
                Pin.autoFromInstagram(groupId, userId,
                        new PlaceSearchHit("k-del", "P-del", "A-del", 37.5, 127.0),
                        "https://www.instagram.com/p/DEL/"));
        pin.delete();
        pinJpaRepository.saveAndFlush(pin);

        // act
        int updated = pinJpaRepository.updateAutoMemoIfNotManual(pin.getId(), userId, "auto-memo");

        // assert : deletedAt IS NULL 조건으로 WHERE 차단 → 0 행
        assertThat(updated).isEqualTo(0);
    }
}
