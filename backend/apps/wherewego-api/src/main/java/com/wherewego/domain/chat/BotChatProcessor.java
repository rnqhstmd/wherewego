package com.wherewego.domain.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherewego.config.env.PlaceProperties;
import com.wherewego.domain.chat.BotPlaceCardsPayloadBuilder.PlaceCardsPayload;
import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.place.PlaceCandidate;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.place.PlaceSearchOutcome;
import com.wherewego.domain.place.PlaceSearchService;
import com.wherewego.domain.place.parser.ContentParser;
import com.wherewego.domain.place.parser.ContentParserRegistry;
import com.wherewego.domain.place.parser.ParsedContent;
import com.wherewego.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * P2 PR-1: 앱 봇 방 1턴 비동기 처리기(FR-4/BR-4/FR-7).
 *
 * <p>{@link BotChatService}가 PROCESSING 커밋 후(afterCommit) {@link #processAsync}를 호출한다.
 * 본 처리기는 "인스타 링크 → Gemini 장소 추출 → 장소 검색 → PLACE_CARDS" 1턴만 담당하며,
 * 카카오 webhook/핸들러/인메모리 세션과는 분리된 stateless 부품(파서 레지스트리·장소 검색)만 재구성한다
 * (설계: webhook/핸들러/세션 완전 무변경). 다중선택/메모 등 멀티턴은 후속 Phase 범위 밖이다.</p>
 *
 * <p>외부 호출(스크래핑/Gemini/장소 검색)은 트랜잭션 밖에서 수행한다. 처리 시간 상한은
 * {@link ContentParser}/{@link PlaceSearchService}로 전파되는 {@link ChatbotContext} 데드라인과
 * 각 HTTP 클라이언트의 read timeout으로 강제되므로(무한 hang 불가) 별도 인터럽트 장치 없이
 * try-catch로만 감싸 실패 시 SYSTEM 안내 메시지로 폴백한다. 결과 append는 {@code repository.save()}가
 * (트랜잭션 없는 @Async 컨텍스트에서) REQUIRED로 개별 트랜잭션 커밋된 직후 STOMP 발행을 동기 호출하므로
 * read-after-write가 안전하다.</p>
 *
 * <p>푸시(APNs) 트리거 배선은 PR-2 범위로, 본 PR에는 포함하지 않는다(STOMP 발행만).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotChatProcessor {

    /**
     * 인스타 URL 1차 판정 패턴. {@code MessageClassifier.INSTAGRAM_URL}(private)과 동일한 리터럴을 둔다.
     * 실제 파싱 지원 여부는 {@link ContentParserRegistry#resolve}가 최종 판정한다.
     */
    private static final Pattern INSTAGRAM_URL = Pattern.compile(
            "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|reels)/[A-Za-z0-9_-]+/?.*"
    );

    private static final String MSG_NOT_INSTAGRAM = "인스타그램 링크를 보내주세요.";
    private static final String MSG_NO_PLACE = "장소를 찾지 못했어요. 앱에서 직접 등록해 주세요.";
    private static final String MSG_FAILED = "처리에 실패했어요. 다시 시도해 주세요.";

    private final ContentParserRegistry contentParserRegistry;
    private final PlaceSearchService placeSearchService;
    private final BotPlaceCardsPayloadBuilder payloadBuilder;
    private final ChatMessageAppender appender;
    private final ChatStompPublisher publisher;
    private final PlaceProperties placeProperties;
    private final ObjectMapper objectMapper;

    /**
     * 봇 1턴 백그라운드 처리. {@link BotChatService#postMessage} 트랜잭션 커밋 후 호출된다.
     *
     * <p>이미 {@code botChatExecutor} 스레드 위에서 실행되므로 {@code doProcess}를 같은 스레드에서
     * 직접 호출한다(예전의 {@code CompletableFuture.supplyAsync(...).orTimeout(...).join()} 래퍼는 제거:
     * commonPool로 작업을 재위임 + {@code join()} 블로킹하여 botChatExecutor 스레드를 낭비하고 풀 포화를
     * 유발했으며, {@code orTimeout}은 ForkJoinPool 스레드의 외부 I/O를 실제로 중단하지 못해 타임아웃이
     * 형식적이었다).</p>
     *
     * <p>처리 시간 상한은 {@code ContentParser}/{@code PlaceSearchService}로 전파되는
     * {@link ChatbotContext} 데드라인 + 각 HTTP 클라이언트(인스타 {@code HttpClient.connectTimeout}/
     * request timeout, Gemini·Google Places {@code SimpleClientHttpRequestFactory} connect/read timeout)의
     * read timeout으로 강제된다 — 별도 30초 인터럽트는 미사용(@Async 단일 스레드에서 하드 인터럽트는
     * 추가 스레드가 필요하여 단일 인스턴스 비용 대비 부적절). 예외/시간초과는 모두 SYSTEM 안내 메시지로
     * 폴백하며, 비동기 스레드이므로 예외를 삼키고 {@code log.warn}만 남긴다(전파해도 무의미).
     * append/publish는 다른 빈에 위임하므로 self-invocation(@Async 프록시 우회) 문제가 없다.</p>
     *
     * @param userId 봇 방 소유자(토픽 {@code /topic/chat/bot/{userId}})
     * @param roomId 봇 방 ID
     * @param text   사용자 입력 원문(인스타 URL 후보)
     */
    @Async("botChatExecutor")
    public void processAsync(Long userId, Long roomId, String text) {
        ChatMessage result;
        try {
            result = doProcess(roomId, text);
        } catch (Exception e) {
            log.warn("봇 1턴 처리 실패 (userId={}, roomId={}): {}", userId, roomId, e.getMessage());
            result = appendSystemSafely(roomId, MSG_FAILED);
        }
        publishSafely(userId, roomId, result);
    }

    /**
     * 트랜잭션 밖 외부 호출 본체. 결과/안내 메시지를 append하여 반환한다.
     * 예외는 호출부({@link #processAsync})에서 SYSTEM 폴백으로 처리하므로 여기서는 잡지 않는다.
     */
    private ChatMessage doProcess(Long roomId, String text) {
        String url = text == null ? "" : text.trim();

        if (!INSTAGRAM_URL.matcher(url).matches()) {
            return appender.appendBotSystem(roomId, MSG_NOT_INSTAGRAM);
        }

        Optional<ContentParser> parserOpt = contentParserRegistry.resolve(url);
        if (parserOpt.isEmpty()) {
            return appender.appendBotSystem(roomId, MSG_NOT_INSTAGRAM);
        }

        List<PlaceSearchHit> hits = extractHits(parserOpt.get(), url);
        if (hits.isEmpty()) {
            return appender.appendBotSystem(roomId, MSG_NO_PLACE);
        }

        PlaceCardsPayload payload = payloadBuilder.build(hits);
        return appender.appendBotPlaceCards(roomId, payload);
    }

    /**
     * ContentParser로 캡션→장소명 추출 → 장소 검색으로 좌표 보강. confident 후보의 첫 hit만 수집한다.
     * 카카오 세션 부수효과 없이 stateless 부품(파서·장소 검색)만 사용한다.
     */
    private List<PlaceSearchHit> extractHits(ContentParser parser, String url) {
        ChatbotContext ctx = ChatbotContext.start(placeProperties.search().syncDeadlineMs());

        Optional<ParsedContent> parsedOpt;
        try {
            parsedOpt = parser.parse(url, ctx);
        } catch (CoreException e) {
            log.warn("봇 1턴 인스타 파싱 실패 code={}", e.getErrorType().getCode());
            return List.of();
        }
        if (parsedOpt.isEmpty()) {
            return List.of();
        }

        List<PlaceCandidate> candidates = parsedOpt.get().candidates();
        if (candidates.isEmpty()) {
            String keyword = parsedOpt.get().placeKeyword();
            if (keyword == null || keyword.isBlank()) {
                return List.of();
            }
            return extractFirstHit(placeSearchService.searchByKeyword(keyword, ctx));
        }

        List<PlaceSearchHit> result = new ArrayList<>(candidates.size());
        HashSet<String> seenName = new HashSet<>();
        for (PlaceCandidate cand : candidates) {
            if (ctx.expired()) {
                log.warn("봇 1턴 데드라인 초과, 추출 중단 ({}/{} 처리)", result.size(), candidates.size());
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

    /**
     * SYSTEM 안내 append 자체가 실패해도 비동기 스레드에서 전파하지 않는다(best-effort).
     */
    private ChatMessage appendSystemSafely(Long roomId, String text) {
        try {
            return appender.appendBotSystem(roomId, text);
        } catch (Exception e) {
            log.warn("봇 1턴 SYSTEM append 실패 (roomId={}): {}", roomId, e.getMessage());
            return null;
        }
    }

    /**
     * STOMP 발행. appender의 {@code repository.save()}가 (트랜잭션 없는 @Async 컨텍스트에서)
     * REQUIRED로 개별 트랜잭션 커밋된 직후 동기 호출되므로 read-after-write가 안전하다.
     * publisher가 best-effort라 추가 방어는 null 가드만 둔다.
     *
     * <p>message가 null이면 결과/실패 안내 append가 모두 실패한 경우다(DB 장애). messageId가 없어
     * 별도 에러 프레임 발행은 무의미하므로 발행은 생략하되, PROCESSING 고아가 STOMP 없이 남는 상황을
     * {@code log.error}로 격상하여 운영 가시성을 확보한다(FR-7).</p>
     */
    private void publishSafely(Long userId, Long roomId, ChatMessage message) {
        if (message == null) {
            log.error("봇 1턴 결과 메시지 append 완전 실패 (userId={}, roomId={}) — STOMP 발행 불가",
                    userId, roomId);
            return;
        }
        publisher.publishBot(userId, ChatMessageFrame.from(message, objectMapper));
    }
}
