package com.wherewego.domain.pin;

import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.group.InviteLinkIssueResult;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PinService} 신규 메서드(listGroupPins/updatePin/softDeletePin) 통합 테스트.
 *
 * <p>활성 그룹원 setup 은 Phase 3 {@link GroupMemberService} 흐름(create → invite → accept) 으로 구성한다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class PinServiceIT {

    @Autowired
    private PinService pinService;

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

    private Long userAId;
    private Long userBId;
    private Long userCId;
    private Long groupId;

    @BeforeEach
    void cleanUp() {
        truncateAll();

        UserModel userA = userJpaRepository.save(UserModel.create(30000001L, "userA", null));
        UserModel userB = userJpaRepository.save(UserModel.create(30000002L, "userB", null));
        UserModel userC = userJpaRepository.save(UserModel.create(30000003L, "userC", null));
        this.userAId = userA.getId();
        this.userBId = userB.getId();
        this.userCId = userC.getId();

        // userA 그룹 + userB 수락 (활성 멤버 2명)
        GroupCreatedResult group = groupMemberService.createGroup(userAId, "우리 지도");
        InviteLinkIssueResult invite = groupMemberService.issueInviteLink(userAId, group.groupId());
        groupMemberService.acceptInviteLink(userBId, invite.token());
        this.groupId = group.groupId();
    }

    @AfterEach
    void tearDown() {
        truncateAll();
    }

    private void truncateAll() {
        // Phase 10 V009: notifications.visit_pin_id → pins(id) FK 추가로 인해 pins 보다 먼저 삭제 필요.
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

    private Pin savePin(Long creatorId, String instagramUrl) {
        return pinRepository.save(Pin.autoFromInstagram(
                groupId, creatorId,
                new PlaceSearchHit("k-" + instagramUrl, "P-" + instagramUrl, "A", 37.5, 127.0),
                instagramUrl));
    }

    @DisplayName("listGroupPins - 활성 그룹원은 그룹의 활성 핀을 created_at 내림차순으로 받는다 (AC-1).")
    @Test
    void listGroupPins_activeMember_returnsActivePinsDesc() throws InterruptedException {
        // arrange : 시간 격차를 둔 3개 핀 (1개는 삭제)
        Pin first = savePin(userAId, "https://www.instagram.com/p/L1/");
        Thread.sleep(10);
        Pin second = savePin(userAId, "https://www.instagram.com/p/L2/");
        Thread.sleep(10);
        Pin third = savePin(userBId, "https://www.instagram.com/p/L3/");

        // second 삭제
        second.delete();
        pinJpaRepository.saveAndFlush(second);

        // act
        List<PinSummary> result = pinService.listGroupPins(userAId, groupId, null);

        // assert : 2개, 최신 등록 순
        assertThat(result).hasSize(2);
        assertThat(result).extracting(PinSummary::id).containsExactly(third.getId(), first.getId());
    }

    @DisplayName("listGroupPins - tag 필터를 적용해 REEL/MEMORY 만 반환한다 (AC-2).")
    @Test
    void listGroupPins_withTagFilter_returnsFiltered() {
        // arrange : REEL 2 + MEMORY 1
        Pin reel1 = savePin(userAId, "https://www.instagram.com/p/F1/");
        Pin reel2 = savePin(userAId, "https://www.instagram.com/p/F2/");
        Pin memory1 = savePin(userBId, "https://www.instagram.com/p/F3/");
        memory1.changeTag(PinTag.MEMORY);
        pinJpaRepository.saveAndFlush(memory1);

        // act
        List<PinSummary> reelOnly = pinService.listGroupPins(userAId, groupId, PinTag.REEL);
        List<PinSummary> memoryOnly = pinService.listGroupPins(userAId, groupId, PinTag.MEMORY);

        // assert
        assertThat(reelOnly).hasSize(2);
        assertThat(reelOnly).extracting(PinSummary::id)
                .containsExactlyInAnyOrder(reel1.getId(), reel2.getId());
        assertThat(memoryOnly).hasSize(1);
        assertThat(memoryOnly).extracting(PinSummary::id).containsExactly(memory1.getId());
    }

    @DisplayName("listGroupPins - 비활성 멤버는 GROUP_NOT_MEMBER 를 반환한다 (AC-3).")
    @Test
    void listGroupPins_nonMember_throwsGroupNotMember() {
        // act & assert : userC 는 그룹 비멤버
        assertThatThrownBy(() -> pinService.listGroupPins(userCId, groupId, null))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.GROUP_NOT_MEMBER);
    }

    @DisplayName("listGroupPins - 핀이 0건이면 빈 리스트를 반환한다 (AC-4).")
    @Test
    void listGroupPins_noPins_returnsEmpty() {
        // act
        List<PinSummary> result = pinService.listGroupPins(userAId, groupId, null);

        // assert
        assertThat(result).isEmpty();
    }

    @DisplayName("updatePin - memo 만 전달하면 memo 와 memoSource=MANUAL 만 갱신되고 tag 는 유지된다 (AC-7).")
    @Test
    void updatePin_memoOnly_updatesMemoAndKeepsTag() {
        // arrange
        Pin pin = savePin(userAId, "https://www.instagram.com/p/U1/");
        PinTag originalTag = pin.getTag();

        // act
        PinUpdateCommand cmd = PinUpdateCommand.of(true, "수동", false, null,
                false, null, false, null, false, null, null);
        PinSummary result = pinService.updatePin(userAId, groupId, pin.getId(), cmd).summary();

        // assert
        assertThat(result.memo()).isEqualTo("수동");
        assertThat(result.memoSource()).isEqualTo(MemoSource.MANUAL);
        assertThat(result.tag()).isEqualTo(originalTag);
    }

    @DisplayName("updatePin - tag 만 전달하면 tag 만 갱신되고 memo/memoSource 는 유지된다 (AC-8).")
    @Test
    void updatePin_tagOnly_updatesTagAndKeepsMemo() {
        // arrange : 기존 memo 가 있는 핀
        Pin pin = savePin(userAId, "https://www.instagram.com/p/U2/");
        pin.applyManualMemo("기존 메모", userAId);
        pinJpaRepository.saveAndFlush(pin);

        // act
        PinUpdateCommand cmd = PinUpdateCommand.of(false, null, true, PinTag.MEMORY,
                false, null, false, null, false, null, null);
        PinSummary result = pinService.updatePin(userAId, groupId, pin.getId(), cmd).summary();

        // assert
        assertThat(result.tag()).isEqualTo(PinTag.MEMORY);
        assertThat(result.memo()).isEqualTo("기존 메모");
        assertThat(result.memoSource()).isEqualTo(MemoSource.MANUAL);
    }

    @DisplayName("updatePin - 빈 문자열 memo 는 memo=null/memoSource=null 로 리셋하고 이후 updateAutoMemoIfNotManual 이 1행을 갱신한다 (AC-11).")
    @Test
    @Transactional
    void updatePin_emptyMemo_clearsLockAndAllowsAuto() {
        // arrange : MANUAL 메모 있는 핀
        Pin pin = savePin(userAId, "https://www.instagram.com/p/U3/");
        pin.applyManualMemo("기존 수동", userAId);
        pinJpaRepository.saveAndFlush(pin);

        // act : 빈 문자열 전송 → 잠금 해제
        PinUpdateCommand cmd = PinUpdateCommand.of(true, "", false, null,
                false, null, false, null, false, null, null);
        PinSummary result = pinService.updatePin(userAId, groupId, pin.getId(), cmd).summary();

        // assert : DB 값도 NULL
        assertThat(result.memo()).isNull();
        assertThat(result.memoSource()).isNull();

        // 이후 AUTO 메모 갱신 가능
        int updated = pinJpaRepository.updateAutoMemoIfNotManual(pin.getId(), userAId, "auto-memo");
        assertThat(updated).isEqualTo(1);
    }

    @DisplayName("updatePin - 수동 메모 저장 후 updateAutoMemoIfNotManual 은 0행 갱신한다 (AC-10).")
    @Test
    @Transactional
    void updatePin_manualMemo_blocksAutoUpdate() {
        // arrange
        Pin pin = savePin(userAId, "https://www.instagram.com/p/U4/");

        // act : 수동 메모 저장
        PinUpdateCommand cmd = PinUpdateCommand.of(true, "수동 메모", false, null,
                false, null, false, null, false, null, null);
        pinService.updatePin(userAId, groupId, pin.getId(), cmd);

        // assert : AUTO 메모 시도는 차단
        int updated = pinJpaRepository.updateAutoMemoIfNotManual(pin.getId(), userAId, "auto-memo");
        assertThat(updated).isEqualTo(0);
    }

    @DisplayName("updatePin - 비활성(비멤버) 사용자는 GROUP_NOT_MEMBER 를 반환한다 (AC-15).")
    @Test
    void updatePin_nonMember_throwsGroupNotMember() {
        // arrange
        Pin pin = savePin(userAId, "https://www.instagram.com/p/U5/");

        // act & assert
        PinUpdateCommand cmd = PinUpdateCommand.of(true, "x", false, null,
                false, null, false, null, false, null, null);
        assertThatThrownBy(() -> pinService.updatePin(userCId, groupId, pin.getId(), cmd))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.GROUP_NOT_MEMBER);
    }

    @DisplayName("updatePin - 이미 삭제된 핀은 PIN_NOT_FOUND 를 반환한다 (AC-14).")
    @Test
    void updatePin_deletedPin_throwsPinNotFound() {
        // arrange : 핀 생성 후 soft delete
        Pin pin = savePin(userAId, "https://www.instagram.com/p/U6/");
        pin.delete();
        pinJpaRepository.saveAndFlush(pin);

        // act & assert
        PinUpdateCommand cmd = PinUpdateCommand.of(true, "x", false, null,
                false, null, false, null, false, null, null);
        assertThatThrownBy(() -> pinService.updatePin(userAId, groupId, pin.getId(), cmd))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_NOT_FOUND);
    }

    @DisplayName("updatePin - placeName 만 전달하면 placeName 만 갱신되고 address 는 유지된다 (Phase 2.8 AC-6).")
    @Test
    void updatePin_placeNameOnly_updatesPlaceNameAndKeepsAddress() {
        // arrange
        Pin pin = savePin(userAId, "https://www.instagram.com/p/U7/");
        String originalAddress = pin.getAddress();

        // act
        PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                true, "새 장소", false, null, false, null, null);
        PinSummary result = pinService.updatePin(userAId, groupId, pin.getId(), cmd).summary();

        // assert
        assertThat(result.placeName()).isEqualTo("새 장소");
        assertThat(result.address()).isEqualTo(originalAddress);

        // DB 영속 확인
        Pin reloaded = pinJpaRepository.findById(pin.getId()).orElseThrow();
        assertThat(reloaded.getPlaceName()).isEqualTo("새 장소");
    }

    @DisplayName("updatePin - 비-멤버가 placeName 수정 시도 시 GROUP_NOT_MEMBER 를 반환한다 (Phase 2.8 AC-9).")
    @Test
    void updatePin_placeNameByNonMember_throwsGroupNotMember() {
        // arrange
        Pin pin = savePin(userAId, "https://www.instagram.com/p/U8/");

        // act & assert : userC 는 비멤버
        PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                true, "새 장소", false, null, false, null, null);
        assertThatThrownBy(() -> pinService.updatePin(userCId, groupId, pin.getId(), cmd))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.GROUP_NOT_MEMBER);
    }

    @DisplayName("updatePin - 등록자가 아닌 활성 멤버도 placeName 을 수정할 수 있다 (Phase 2.8 BR-3).")
    @Test
    void updatePin_placeNameByAnotherActiveMember_succeeds() {
        // arrange : userA 가 등록, userB 가 수정
        Pin pin = savePin(userAId, "https://www.instagram.com/p/U9/");

        // act
        PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                true, "userB 수정", false, null, false, null, null);
        PinSummary result = pinService.updatePin(userBId, groupId, pin.getId(), cmd).summary();

        // assert
        assertThat(result.placeName()).isEqualTo("userB 수정");
    }

    @DisplayName("updatePin - address 만 전달하면 address 만 갱신되고 placeName 은 유지된다 (Phase 2.8 AC-6).")
    @Test
    void updatePin_addressOnly_updatesAddressOnly() {
        // arrange
        Pin pin = savePin(userAId, "https://www.instagram.com/p/UAO/");
        String originalPlaceName = pin.getPlaceName();

        // act
        PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                false, null, true, "새 주소", false, null, null);
        PinSummary result = pinService.updatePin(userAId, groupId, pin.getId(), cmd).summary();

        // assert
        assertThat(result.address()).isEqualTo("새 주소");
        assertThat(result.placeName()).isEqualTo(originalPlaceName);

        // DB 영속 확인
        Pin reloaded = pinJpaRepository.findById(pin.getId()).orElseThrow();
        assertThat(reloaded.getAddress()).isEqualTo("새 주소");
        assertThat(reloaded.getPlaceName()).isEqualTo(originalPlaceName);
    }

    @DisplayName("updatePin - placeName 과 address 를 동시에 수정하면 둘 다 갱신된다 (Phase 2.8 동시 수정).")
    @Test
    void updatePin_placeNameAndAddress_updatesBoth() {
        // arrange
        Pin pin = savePin(userAId, "https://www.instagram.com/p/UA/");

        // act
        PinUpdateCommand cmd = PinUpdateCommand.of(false, null, false, null,
                true, "새 장소", true, "새 주소", false, null, null);
        PinSummary result = pinService.updatePin(userAId, groupId, pin.getId(), cmd).summary();

        // assert
        assertThat(result.placeName()).isEqualTo("새 장소");
        assertThat(result.address()).isEqualTo("새 주소");
    }

    @DisplayName("softDeletePin - 삭제 후 listGroupPins 에서 미반환된다 (AC-16).")
    @Test
    void softDeletePin_removesFromListing() {
        // arrange
        Pin pin = savePin(userAId, "https://www.instagram.com/p/D1/");

        // act
        pinService.softDeletePin(userAId, groupId, pin.getId());

        // assert : 목록 미반환
        List<PinSummary> result = pinService.listGroupPins(userAId, groupId, null);
        assertThat(result).isEmpty();

        // DB : deleted_at 기록
        Pin reloaded = pinJpaRepository.findById(pin.getId()).orElseThrow();
        assertThat(reloaded.isDeleted()).isTrue();
    }

    @DisplayName("softDeletePin - 등록자 본인이 아닌 활성 그룹원도 삭제 가능하다 (AC-19).")
    @Test
    void softDeletePin_byAnotherActiveMember_succeeds() {
        // arrange : userA 가 등록한 핀
        Pin pin = savePin(userAId, "https://www.instagram.com/p/D2/");

        // act : userB(파트너) 가 삭제
        pinService.softDeletePin(userBId, groupId, pin.getId());

        // assert
        Pin reloaded = pinJpaRepository.findById(pin.getId()).orElseThrow();
        assertThat(reloaded.isDeleted()).isTrue();
    }

    @DisplayName("softDeletePin - 이미 삭제된 핀은 PIN_NOT_FOUND 를 반환한다 (AC-17).")
    @Test
    void softDeletePin_alreadyDeleted_throwsPinNotFound() {
        // arrange : 핀 생성 후 삭제
        Pin pin = savePin(userAId, "https://www.instagram.com/p/D3/");
        pinService.softDeletePin(userAId, groupId, pin.getId());

        // act & assert : 두 번째 삭제 시도
        assertThatThrownBy(() -> pinService.softDeletePin(userAId, groupId, pin.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.PIN_NOT_FOUND);
    }

    @DisplayName("softDeletePin - 비활성(비멤버) 사용자는 GROUP_NOT_MEMBER 를 반환한다 (AC-18).")
    @Test
    void softDeletePin_nonMember_throwsGroupNotMember() {
        // arrange
        Pin pin = savePin(userAId, "https://www.instagram.com/p/D4/");

        // act & assert
        assertThatThrownBy(() -> pinService.softDeletePin(userCId, groupId, pin.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.GROUP_NOT_MEMBER);
    }

    @DisplayName("listGroupPinsPaged - 25개 핀에서 page=0/size=10 → items 10, totalCount 25, hasNext true.")
    @Test
    void listGroupPinsPaged_returnsCorrectSliceAndTotal() {
        // arrange : 25개 핀
        for (int i = 0; i < 25; i++) {
            savePin(userAId, "https://www.instagram.com/p/PG" + i + "/");
        }

        // act
        PinListResult result = pinService.listGroupPinsPaged(userAId, groupId, null, 0, 10);

        // assert
        assertThat(result.items()).hasSize(10);
        assertThat(result.totalCount()).isEqualTo(25L);
        assertThat(result.hasNext()).isTrue();
    }

    @DisplayName("listGroupPinsPaged - 25개 핀에서 마지막 페이지(page=2/size=10) → items 5, hasNext false.")
    @Test
    void listGroupPinsPaged_lastPage_hasNextFalse() {
        // arrange : 25개 핀
        for (int i = 0; i < 25; i++) {
            savePin(userAId, "https://www.instagram.com/p/PL" + i + "/");
        }

        // act
        PinListResult result = pinService.listGroupPinsPaged(userAId, groupId, null, 2, 10);

        // assert
        assertThat(result.items()).hasSize(5);
        assertThat(result.totalCount()).isEqualTo(25L);
        assertThat(result.hasNext()).isFalse();
    }

    @DisplayName("listGroupPinsPaged - 비활성(비멤버) 사용자는 GROUP_NOT_MEMBER 를 반환한다.")
    @Test
    void listGroupPinsPaged_nonMember_throwsGroupNotMember() {
        // act & assert : userC 는 그룹 비멤버
        assertThatThrownBy(() -> pinService.listGroupPinsPaged(userCId, groupId, null, 0, 10))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.GROUP_NOT_MEMBER);
    }

    @DisplayName("listGroupPinsPaged - tag 필터는 count 와 items 모두 반영된다.")
    @Test
    void listGroupPinsPaged_withTagFilter() {
        // arrange : REEL 15 + MEMORY 10
        for (int i = 0; i < 15; i++) {
            savePin(userAId, "https://www.instagram.com/p/TPF" + i + "/");
        }
        for (int i = 0; i < 10; i++) {
            Pin memory = savePin(userBId, "https://www.instagram.com/p/TMF" + i + "/");
            memory.changeTag(PinTag.MEMORY);
            pinJpaRepository.saveAndFlush(memory);
        }

        // act
        PinListResult reelOnly = pinService.listGroupPinsPaged(userAId, groupId, PinTag.REEL, 0, 10);
        PinListResult memoryOnly = pinService.listGroupPinsPaged(userAId, groupId, PinTag.MEMORY, 0, 10);

        // assert : count 가 태그 필터를 반영
        assertThat(reelOnly.totalCount()).isEqualTo(15L);
        assertThat(reelOnly.items()).hasSize(10);
        assertThat(reelOnly.hasNext()).isTrue();
        assertThat(reelOnly.items()).allMatch(s -> s.tag() == PinTag.REEL);

        assertThat(memoryOnly.totalCount()).isEqualTo(10L);
        assertThat(memoryOnly.items()).hasSize(10);
        assertThat(memoryOnly.hasNext()).isFalse();
        assertThat(memoryOnly.items()).allMatch(s -> s.tag() == PinTag.MEMORY);
    }
}
