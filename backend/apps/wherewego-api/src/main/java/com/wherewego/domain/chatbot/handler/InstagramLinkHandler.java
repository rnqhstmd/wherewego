package com.wherewego.domain.chatbot.handler;

import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.MessageType;
import com.wherewego.domain.chatbot.ReelSavedSelectionSession;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.place.PlaceCandidate;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.place.PlaceSearchOutcome;
import com.wherewego.domain.place.PlaceSearchService;
import com.wherewego.domain.place.parser.ContentParser;
import com.wherewego.domain.place.parser.ContentParserRegistry;
import com.wherewego.domain.place.parser.ParsedContent;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import com.wherewego.support.error.CoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Phase 12: 인스타그램 URL 수신 진입점.
 *
 * <p>Gemini/카카오 추출 결과 개수에 따라 {@link ReelSavedSelectionSession} 상태머신의 초기 단계로 진입한다 (Phase 13):
 * <ul>
 *     <li>0개: 안내 후 종료 (세션 진입 없음)</li>
 *     <li>1개: {@code MULTI_SELECTING} — [가고 싶어요]/[그냥 저장] QuickReply (선택=위시, 미선택=발견)</li>
 *     <li>2~30개: {@code MULTI_SELECTING} — 번호 목록 + [전부]/[건너뛰기] QuickReply (선택=위시, 나머지=발견)</li>
 *     <li>31개 이상: {@code BULK_SAVE} — 전체 발견 저장 안내 + 메모 직접 입력</li>
 * </ul>
 *
 * <p>새 URL 도착 시 활성 세션이 있으면 {@link com.wherewego.domain.chatbot.ChatbotWebhookService}
 * 의 가드가 이전 세션을 자동 저장 후 본 핸들러로 진입시킨다 (EC-U 시리즈).</p>
 */
@Component
public class InstagramLinkHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(InstagramLinkHandler.class);

    private static final int BULK_THRESHOLD = 31;

    private final BotUserMappingService botUserMappingService;
    private final GroupMemberService groupMemberService;
    private final ContentParserRegistry contentParserRegistry;
    private final PlaceSearchService placeSearchService;
    private final ReelSavedSelectionSession reelSavedSelectionSession;
    private final long reelSelectionTtlSeconds;

    public InstagramLinkHandler(BotUserMappingService botUserMappingService,
                                GroupMemberService groupMemberService,
                                ContentParserRegistry contentParserRegistry,
                                PlaceSearchService placeSearchService,
                                ReelSavedSelectionSession reelSavedSelectionSession,
                                @Value("${chatbot.reel.selection-ttl-seconds:180}") long reelSelectionTtlSeconds) {
        this.botUserMappingService = botUserMappingService;
        this.groupMemberService = groupMemberService;
        this.contentParserRegistry = contentParserRegistry;
        this.placeSearchService = placeSearchService;
        this.reelSavedSelectionSession = reelSavedSelectionSession;
        this.reelSelectionTtlSeconds = reelSelectionTtlSeconds;
    }

    @Override
    public MessageType supports() {
        return MessageType.INSTAGRAM_LINK;
    }

    @Override
    public ChatbotV1Dto.SkillResponse handle(ChatbotV1Dto.SkillRequest request, ChatbotContext ctx) {
        String botUserKey = request.userRequest().user().id();
        String url = request.userRequest().utterance().trim();

        Long userId = ctx.userId();
        if (userId == null) {
            Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
            if (userIdOpt.isEmpty()) {
                return ChatbotV1Dto.SkillResponse.simple(
                        "먼저 그룹 연동이 필요해요. 챗봇 메뉴에서 [🔗 그룹 연동하기]를 눌러주세요.");
            }
            userId = userIdOpt.get();
        }

        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("그룹에 먼저 참여해주세요.");
        }

        Optional<ContentParser> parserOpt = contentParserRegistry.resolve(url);
        if (parserOpt.isEmpty()) {
            return ChatbotV1Dto.SkillResponse.simple("지원하지 않는 링크입니다.");
        }

        // Gemini/카카오 추출 — PROCESSING 단계로 간주. 세션은 결과 분기 시 등록한다.
        List<PlaceSearchHit> hits = extractHits(parserOpt.get(), url, ctx);
        int count = hits.size();
        log.info("InstagramLink extracted botUserKey={} url={} count={}", botUserKey, url, count);

        if (count == 0) {
            return ChatbotV1Dto.SkillResponse.simple(
                    "장소를 찾지 못했어요. 앱에서 직접 등록해 주세요.");
        }
        if (count == 1) {
            return enterSingleSelecting(botUserKey, url, hits);
        }
        if (count < BULK_THRESHOLD) {
            return enterMultiSelecting(botUserKey, url, hits);
        }
        return enterBulkSave(botUserKey, url, hits);
    }

    /**
     * ContentParser 로 추출 → Kakao Local 로 좌표 보강. {@link PlaceCandidate} 가 confident=true 인 경우만 채택.
     * Single/Multiple 모두 첫 번째 hit 만 수집한다 (Multiple 의 경우 1순위 hit 가 가장 일치도 높음).
     */
    private List<PlaceSearchHit> extractHits(ContentParser parser, String url, ChatbotContext ctx) {
        Optional<ParsedContent> parsedOpt;
        try {
            parsedOpt = parser.parse(url, ctx);
        } catch (CoreException e) {
            log.warn("Instagram parse failed code={}", e.getErrorType().getCode());
            return List.of();
        }
        if (parsedOpt.isEmpty()) {
            return List.of();
        }
        List<PlaceCandidate> candidates = parsedOpt.get().candidates();
        if (candidates.isEmpty()) {
            // Legacy 단일 키워드 — placeKeyword 기준 1회 검색.
            String keyword = parsedOpt.get().placeKeyword();
            if (keyword == null || keyword.isBlank()) {
                return List.of();
            }
            PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(keyword, ctx);
            return extractFirstHit(outcome);
        }
        List<PlaceSearchHit> result = new ArrayList<>(candidates.size());
        HashSet<String> seenName = new HashSet<>();
        for (PlaceCandidate cand : candidates) {
            if (ctx.expired()) {
                log.warn("InstagramLink deadline hit during extraction, {} remaining",
                        candidates.size() - result.size());
                break;
            }
            if (!cand.confident()) {
                continue;
            }
            String nameKey = cand.name().trim().toLowerCase();
            if (!seenName.add(nameKey)) {
                continue;
            }
            PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(cand.name(), ctx);
            result.addAll(extractFirstHit(outcome));
        }
        return result;
    }

    private static List<PlaceSearchHit> extractFirstHit(PlaceSearchOutcome outcome) {
        if (outcome instanceof PlaceSearchOutcome.Single single) {
            return List.of(single.hit());
        }
        if (outcome instanceof PlaceSearchOutcome.Multiple multiple) {
            List<PlaceSearchHit> hits = multiple.hits();
            if (!hits.isEmpty()) {
                return List.of(hits.get(0));
            }
        }
        return List.of();
    }

    /**
     * Phase 13 통합: 1곳 추출도 MULTI_SELECTING 선택 단계로 진입한다. 번호가 1개뿐이라 콤마 입력 대신
     * [가고 싶어요](=전부 위시) / [그냥 저장](=전부 발견) QuickReply 로 단순화하며, 발화는
     * {@link ReelMultiSelectionHandler} 가 통합 처리한다 (구 SINGLE_WANT 단계/핸들러 폐기).
     */
    private ChatbotV1Dto.SkillResponse enterSingleSelecting(String botUserKey, String url, List<PlaceSearchHit> hits) {
        ReelSavedSelectionSession.Snapshot snapshot = new ReelSavedSelectionSession.Snapshot(
                ReelSavedSelectionSession.State.MULTI_SELECTING,
                url,
                hits,
                new HashSet<>(),
                ZonedDateTime.now().plusSeconds(reelSelectionTtlSeconds),
                null
        );
        reelSavedSelectionSession.put(botUserKey, snapshot);
        PlaceSearchHit hit = hits.get(0);
        return ChatbotV1Dto.SkillResponse.simple(
                "릴스에서 장소 1개를 찾았어요:\n• " + hit.placeName() + "\n\n"
                        + "가고 싶은 곳이면 위시로 저장할게요.",
                List.of(
                        ChatbotV1Dto.QuickReply.message("가고 싶어요", "가고 싶어요"),
                        ChatbotV1Dto.QuickReply.message("그냥 저장", "그냥 저장")
                )
        );
    }

    private ChatbotV1Dto.SkillResponse enterMultiSelecting(String botUserKey, String url, List<PlaceSearchHit> hits) {
        ReelSavedSelectionSession.Snapshot snapshot = new ReelSavedSelectionSession.Snapshot(
                ReelSavedSelectionSession.State.MULTI_SELECTING,
                url,
                hits,
                new HashSet<>(),
                ZonedDateTime.now().plusSeconds(reelSelectionTtlSeconds),
                null
        );
        reelSavedSelectionSession.put(botUserKey, snapshot);
        StringBuilder sb = new StringBuilder();
        sb.append("릴스에서 장소 ").append(hits.size())
                .append("개를 찾았어요. ✨ 가고 싶은 곳 번호를 콤마로 보내면 위시로 저장할게요. (나머지는 발견) 예: 1,3,5\n\n");
        for (int i = 0; i < hits.size(); i++) {
            sb.append(i + 1).append(". ").append(hits.get(i).placeName()).append('\n');
        }
        return ChatbotV1Dto.SkillResponse.simple(
                sb.toString().trim(),
                List.of(
                        ChatbotV1Dto.QuickReply.message("전부", "전부"),
                        ChatbotV1Dto.QuickReply.message("건너뛰기", "건너뛰기")
                )
        );
    }

    private ChatbotV1Dto.SkillResponse enterBulkSave(String botUserKey, String url, List<PlaceSearchHit> hits) {
        ReelSavedSelectionSession.Snapshot snapshot = new ReelSavedSelectionSession.Snapshot(
                ReelSavedSelectionSession.State.BULK_SAVE,
                url,
                hits,
                new HashSet<>(),
                ZonedDateTime.now().plusSeconds(reelSelectionTtlSeconds),
                null
        );
        reelSavedSelectionSession.put(botUserKey, snapshot);
        return ChatbotV1Dto.SkillResponse.simple(
                "릴스에서 장소 " + hits.size() + "개를 찾았어요. 전체 발견으로 저장할게요.\n\n"
                        + "함께 남길 메모를 입력해주세요. (메모가 없으면 [건너뛰기])",
                List.of(ChatbotV1Dto.QuickReply.message("건너뛰기", "건너뛰기"))
        );
    }

}
