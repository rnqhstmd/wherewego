package com.wherewego.support.demo;

import com.wherewego.domain.auth.jwt.RefreshTokenHasher;
import com.wherewego.domain.chat.BotPlaceCardsPayloadBuilder;
import com.wherewego.domain.chat.BotPlaceCardsPayloadBuilder.PlaceCardsPayload;
import com.wherewego.domain.chat.ChatMessageAppender;
import com.wherewego.domain.chat.ChatRoom;
import com.wherewego.domain.chat.ChatRoomRepository;
import com.wherewego.domain.group.Group;
import com.wherewego.domain.group.GroupMember;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupRepository;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.user.OauthProvider;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.demo.DemoSeedProperties.DemoUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * P5 FR-25/QE-3/AC-18: 앱스토어 리뷰어 데모 계정·시드 데이터를 멱등 생성한다.
 *
 * <p>{@code InviteLinkBackfillRunner} 와 동일하게 {@link ApplicationRunner} + 메서드 레벨
 * {@link Transactional} 로 기동 시 1회 실행된다. {@code wherewego.demo-seed.enabled=true} 일 때만
 * 활성({@link ConditionalOnProperty})되어 운영/일반 환경에는 빈으로 등록되지 않는다.</p>
 *
 * <p><b>멱등성</b>: primary 데모 사용자(user1)를 (provider, oauthId)로 조회해 이미 존재하면 시드를
 * 전부 건너뛴다(refresh token 해시만 재설정 — 재기동·재시드 후에도 동일 시드 토큰이 유효하도록).
 * 신규 시드 경로에서만 사용자 2명·그룹·멤버십·봇 방 대화·커플 방 대화·핀을 생성한다.</p>
 *
 * <p>봇 방 PLACE_CARDS 1건은 {@link ChatMessageAppender}/{@link BotPlaceCardsPayloadBuilder} 를
 * 경유해 운영 스키마와 정합한 payload 로 저장한다.</p>
 *
 * <p>BR-6/FR-27(AC-20): 인스타 릴스 흐름은 URL 텍스트만 봇 방에 적재하며 미디어 원본은 저장하지 않는다.
 * 데모 시드도 동일하게 릴스 URL 텍스트 1건만 적재한다.</p>
 */
@Component
@ConditionalOnProperty(prefix = "wherewego.demo-seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    /** BR-6/FR-27: 데모 봇 방에 적재할 릴스 URL 텍스트(미디어 원본 미저장). */
    private static final String DEMO_REEL_URL = "https://www.instagram.com/reel/DEMO_SEED_REEL/";

    private final DemoSeedProperties properties;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageAppender chatMessageAppender;
    private final BotPlaceCardsPayloadBuilder placeCardsPayloadBuilder;
    private final PinRepository pinRepository;
    private final RefreshTokenHasher refreshTokenHasher;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.warn("데모 시드 활성화되었으나 식별자 미설정 — 시드를 건너뜁니다. (wherewego.demo-seed.user1/user2)");
            return;
        }

        DemoUser u1 = properties.user1();
        DemoUser u2 = properties.user2();

        UserModel user1 = findDemoUser(u1).orElse(null);
        if (user1 != null) {
            // 이미 시드됨 — 멱등 종료. refresh token 해시만 재설정해 시드 토큰 재사용성을 보장한다.
            applyDemoRefreshToken(user1);
            log.info("데모 시드 이미 존재 — 건너뜀(refresh token 재설정). userId={}", user1.getId());
            return;
        }

        // --- 신규 시드 ---
        UserModel demoUser1 = userRepository.save(
                UserModel.createOauth(u1.oauthProvider(), u1.oauthId(), u1.nickname(), null, null));
        UserModel demoUser2 = userRepository.save(
                UserModel.createOauth(u2.oauthProvider(), u2.oauthId(), u2.nickname(), null, null));

        Instant now = Instant.now();
        Group group = groupRepository.save(Group.create(properties.groupName()));
        groupMemberRepository.save(GroupMember.createActive(group.getId(), demoUser1.getId(), now));
        groupMemberRepository.save(GroupMember.createActive(group.getId(), demoUser2.getId(), now));

        seedBotRoom(demoUser1.getId());
        seedCoupleRoom(group.getId(), demoUser1.getId(), demoUser2.getId());
        seedPins(group.getId(), demoUser1.getId(), demoUser2.getId());

        applyDemoRefreshToken(demoUser1);

        log.info("데모 시드 완료: user1={} user2={} groupId={}",
                demoUser1.getId(), demoUser2.getId(), group.getId());
    }

    private java.util.Optional<UserModel> findDemoUser(DemoUser demoUser) {
        return userRepository.findByOauthProviderAndOauthIdAndDeletedAtIsNull(
                demoUser.oauthProvider(), demoUser.oauthId());
    }

    /**
     * primary 데모 사용자의 refreshTokenHash 를 시드 refresh token 의 해시로 설정한다(§10).
     * 평문 토큰이 비어 있으면(BR-7 미주입) 건너뛴다 — 데모 로그인이 비활성된다.
     */
    private void applyDemoRefreshToken(UserModel user) {
        String raw = properties.refreshToken();
        if (raw == null || raw.isBlank()) {
            log.warn("데모 refresh token 미주입 — 데모 로그인 비활성(refresh 회전 예외 무효). userId={}", user.getId());
            return;
        }
        user.replaceRefreshTokenHash(refreshTokenHasher.sha256Hex(raw));
        userRepository.save(user);
    }

    /**
     * 봇 방(유저별) 대화 3건: 릴스 URL(USER TEXT) → 처리중(BOT PROCESSING) → 장소 카드(BOT PLACE_CARDS).
     * PLACE_CARDS 는 {@link BotPlaceCardsPayloadBuilder} 로 운영과 동일한 payload 스키마를 생성한다.
     */
    private void seedBotRoom(Long ownerUserId) {
        ChatRoom botRoom = chatRoomRepository.findActiveBotRoom(ownerUserId)
                .orElseGet(() -> chatRoomRepository.save(ChatRoom.createBotRoom(ownerUserId)));
        Long roomId = botRoom.getId();

        chatMessageAppender.appendUserText(roomId, ownerUserId, DEMO_REEL_URL);
        chatMessageAppender.appendBotProcessing(roomId);

        PlaceCardsPayload payload = placeCardsPayloadBuilder.build(List.of(
                new PlaceSearchHit("11111111", "성수 어니언", "서울 성동구 아차산로9길 8", 37.5443, 127.0557),
                new PlaceSearchHit("22222222", "블루보틀 성수", "서울 성동구 아차산로 7", 37.5450, 127.0561)
        ));
        chatMessageAppender.appendBotPlaceCards(roomId, payload);
    }

    /**
     * 커플 방 메시지 3건(두 파트너 간 TEXT 대화). 봇 미개입(FR-9).
     */
    private void seedCoupleRoom(Long groupId, Long user1Id, Long user2Id) {
        ChatRoom coupleRoom = chatRoomRepository.findActiveCoupleRoom(groupId)
                .orElseGet(() -> chatRoomRepository.save(ChatRoom.createCoupleRoom(groupId)));
        Long roomId = coupleRoom.getId();

        chatMessageAppender.appendCoupleText(roomId, user1Id, "이번 주말에 성수 갈래?");
        chatMessageAppender.appendCoupleText(roomId, user2Id, "좋아! 어니언 가보고 싶었어");
        chatMessageAppender.appendCoupleText(roomId, user1Id, "그럼 토요일 오전에 보자");
    }

    /**
     * 핀 3건(태그 혼합: REEL/WISH/MEMORY). 시드 컨텍스트라 멤버십 검증을 우회해 리포지토리에 직접 저장한다
     * (설계: PinService 또는 리포지토리 경유 허용).
     */
    private void seedPins(Long groupId, Long user1Id, Long user2Id) {
        // BR-6 정합: 런타임 앱은 REEL 핀에 instagramUrl 을 설정하지 않으므로(릴스 URL 은 봇 방 텍스트로만 전달)
        // 데모 시드 REEL 핀도 instagramUrl=null 로 생성한다(더미 URL 404·앱 불일치 방지). URL 텍스트는 봇 방에만 유지.
        pinRepository.save(Pin.fromSelection(groupId, user1Id,
                new PlaceSearchHit("11111111", "성수 어니언", "서울 성동구 아차산로9길 8", 37.5443, 127.0557),
                null, PinTag.REEL));
        pinRepository.save(Pin.createFromUser(groupId, user2Id,
                "도쿄 시부야 스카이", "일본 도쿄도 시부야구",
                java.math.BigDecimal.valueOf(35.6595), java.math.BigDecimal.valueOf(139.7005),
                null, PinTag.WISH));
        pinRepository.save(Pin.createFromUser(groupId, user1Id,
                "제주 협재 해수욕장", "제주특별자치도 제주시 한림읍 협재리",
                java.math.BigDecimal.valueOf(33.3940), java.math.BigDecimal.valueOf(126.2396),
                null, PinTag.MEMORY));
    }
}
