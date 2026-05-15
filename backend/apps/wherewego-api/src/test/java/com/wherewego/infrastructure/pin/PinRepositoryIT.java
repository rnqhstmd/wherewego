package com.wherewego.infrastructure.pin;

import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
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
}
