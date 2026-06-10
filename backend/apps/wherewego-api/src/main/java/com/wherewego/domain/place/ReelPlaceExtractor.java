package com.wherewego.domain.place;

import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.place.parser.ContentParser;
import com.wherewego.domain.place.parser.ContentParserRegistry;
import com.wherewego.domain.place.parser.ParsedContent;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * GC-1: 릴스 URL → 장소 후보 추출 파이프라인(스크래핑 → Gemini → 장소 검색)의 공용 진입점.
 *
 * <p>{@code BotChatProcessor}(봇 1턴)와 그룹 채팅 온디맨드 추출 API(FR-GC1-5)가 공유한다.
 * 봇은 GC-3 에서 제거 예정이므로 추출 로직을 봇 클래스에서 분리해 보존한다. 카카오 세션 부수효과 없이
 * stateless 부품(파서 레지스트리·장소 검색)만 사용하며, 데드라인은 호출자가 주입한다
 * (봇 1턴 = {@code place.search.sync-deadline-ms}, 추출 API = {@code place.search.extract-deadline-ms}).</p>
 *
 * <p>예외 의미는 호출자가 결정한다 — {@link #extract}는 파싱/검색 {@link CoreException}을 그대로 전파하고
 * (추출 API 가 PLC_* 502 로 노출, 재시도 가능), 봇은 {@link #hitsFromParsed} 앞의 파싱을 자체
 * try-catch 로 감싸 기존 폴백 메시지 동작을 보존한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReelPlaceExtractor {

    /**
     * 인스타 URL 1차 판정 패턴. {@code MessageClassifier.INSTAGRAM_URL}/{@code BotChatProcessor}(private)와
     * 동일한 리터럴을 둔다. 실제 파싱 지원 여부는 {@link ContentParserRegistry#resolve}가 최종 판정한다.
     */
    public static final Pattern INSTAGRAM_URL = Pattern.compile(
            "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|reels)/[A-Za-z0-9_-]+/?.*"
    );

    private final ContentParserRegistry contentParserRegistry;
    private final PlaceSearchService placeSearchService;

    /** URL 이 인스타 패턴이고 파서가 존재하는지 판정한다(봇 MSG_NOT_INSTAGRAM 분기용). */
    public boolean supports(String url) {
        return url != null
                && INSTAGRAM_URL.matcher(url).matches()
                && contentParserRegistry.resolve(url).isPresent();
    }

    /**
     * 전체 추출 파이프라인을 동기 실행한다(그룹 채팅 추출 API 용 — FR-GC1-5).
     *
     * <p>파싱/검색 실패의 {@link CoreException}(PLC_INSTAGRAM_SCRAPE_FAILED 등)은 그대로 전파한다 —
     * 호출자가 "추출 실패(재시도 가능)"로 노출한다. 장소를 못 찾은 경우는 빈 리스트(200 + 빈 cards)다.</p>
     *
     * @param url        인스타 릴스 URL(저장 시 이미 검증됨)
     * @param deadlineMs 추출 데드라인(ms)
     * @return 좌표 보강된 장소 후보. 0곳이면 빈 리스트.
     * @throws CoreException 파서 미지원(BAD_REQUEST)·스크래핑/Gemini/장소 검색 실패(PLC_*)
     */
    public List<PlaceSearchHit> extract(String url, long deadlineMs) {
        ContentParser parser = contentParserRegistry.resolve(url)
                .orElseThrow(() -> new CoreException(ErrorType.BAD_REQUEST, "지원하지 않는 링크입니다."));

        ChatbotContext ctx = ChatbotContext.start(deadlineMs);
        Optional<ParsedContent> parsedOpt = parser.parse(url, ctx);
        if (parsedOpt.isEmpty()) {
            return List.of();
        }
        return hitsFromParsed(parsedOpt.get(), ctx);
    }

    /**
     * 파싱 결과의 후보를 장소 검색으로 좌표 보강한다 — confident 후보의 첫 hit 만 수집한다
     * (기존 {@code BotChatProcessor.extractHits} 후반 로직 이동, 동작 동일).
     *
     * <p>후보가 없으면 {@code placeKeyword} 단건 검색으로 폴백한다. 데드라인 초과 시 처리분까지만 반환한다.
     * 장소 검색 {@link CoreException}은 전파한다(호출자가 의미 결정).</p>
     */
    public List<PlaceSearchHit> hitsFromParsed(ParsedContent parsed, ChatbotContext ctx) {
        List<PlaceCandidate> candidates = parsed.candidates();
        if (candidates.isEmpty()) {
            String keyword = parsed.placeKeyword();
            if (keyword == null || keyword.isBlank()) {
                return List.of();
            }
            return extractFirstHit(placeSearchService.searchByKeyword(keyword, ctx));
        }

        List<PlaceSearchHit> result = new ArrayList<>(candidates.size());
        HashSet<String> seenName = new HashSet<>();
        for (PlaceCandidate cand : candidates) {
            if (ctx.expired()) {
                log.warn("릴스 추출 데드라인 초과, 추출 중단 ({}/{} 처리)", result.size(), candidates.size());
                break;
            }
            if (!cand.confident()) {
                continue;
            }
            String nameKey = cand.name().trim().toLowerCase();
            if (!seenName.add(nameKey)) {
                continue;
            }
            result.addAll(extractFirstHit(placeSearchService.searchByKeyword(cand.name(), ctx)));
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
}
