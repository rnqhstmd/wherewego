package com.wherewego.domain.place;

import com.wherewego.domain.chatbot.ChatbotContext;
import com.wherewego.domain.chatbot.FallbackJobContext;
import com.wherewego.domain.chatbot.handler.PlaceCardBuilder;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinService;
import com.wherewego.domain.pin.memo.TwoSecondMemoSession;
import com.wherewego.infrastructure.chatbot.callback.KakaoCallbackClient;
import com.wherewego.infrastructure.notify.slack.SlackNotifier;
import com.wherewego.infrastructure.place.google.GooglePlacesClient;
import com.wherewego.interfaces.api.chatbot.ChatbotV1Dto;
import com.wherewego.support.error.CoreException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 카카오 Local Empty 시 Google Places 폴백을 동기/비동기로 분기한다.
 *
 * <p><b>runSync</b>: 동기 호출 → {@link PlaceSearchOutcome} 반환. 호출자({@code InstagramLinkHandler})가
 * 기존 Single/Multiple/Empty 분기에 재투입한다.</p>
 *
 * <p><b>runAsync</b>: 백그라운드 워커에 작업 제출. queue full 시 {@link java.util.concurrent.RejectedExecutionException}
 * 을 caller에게 전파 (caller가 best-effort 콜백 푸시로 fast-fail).</p>
 */
@Component
public class PlaceFallbackOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PlaceFallbackOrchestrator.class);
    private static final int MAX_MULTIPLE = 5;

    // Google 비동기 호출 시 timeout 강제용 데드라인. timeoutMs + 약간 여유.
    private static final long ASYNC_DEADLINE_MS = 2_000L;

    private final GooglePlacesClient googlePlacesClient;
    private final KakaoCallbackClient kakaoCallbackClient;
    private final SlackNotifier slackNotifier;
    private final PinService pinService;
    private final PlaceSelectionCandidateStore candidateStore;
    private final TwoSecondMemoSession twoSecondMemoSession;
    private final ThreadPoolExecutor executor;

    public PlaceFallbackOrchestrator(GooglePlacesClient googlePlacesClient,
                                     KakaoCallbackClient kakaoCallbackClient,
                                     SlackNotifier slackNotifier,
                                     PinService pinService,
                                     PlaceSelectionCandidateStore candidateStore,
                                     TwoSecondMemoSession twoSecondMemoSession) {
        this.googlePlacesClient = googlePlacesClient;
        this.kakaoCallbackClient = kakaoCallbackClient;
        this.slackNotifier = slackNotifier;
        this.pinService = pinService;
        this.candidateStore = candidateStore;
        this.twoSecondMemoSession = twoSecondMemoSession;

        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "place-fallback-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 동기 폴백. caller(InstagramLinkHandler)는 반환된 Outcome을 기존 Single/Multiple/Empty 분기에 투입한다.
     * Google 실패 시 Slack 알림 후 Empty 반환.
     */
    public PlaceSearchOutcome runSync(String keyword, ChatbotContext ctx) {
        try {
            List<PlaceSearchHit> hits = googlePlacesClient.searchByKeyword(keyword, MAX_MULTIPLE, ctx);
            return toOutcome(hits);
        } catch (Exception e) {
            log.error("Google Places sync fallback failed keyword={} cause={}", keyword, e.getMessage());
            slackNotifier.notifyFailure("Google Places sync fallback failed",
                    buildContext(keyword, null, e));
            return new PlaceSearchOutcome.Empty();
        }
    }

    /**
     * 비동기 폴백 제출. queue full → {@link java.util.concurrent.RejectedExecutionException} 을 그대로 throw.
     * caller (InstagramLinkHandler) 가 catch하여 best-effort 콜백 푸시 + fast-fail.
     */
    public void runAsync(String keyword, FallbackJobContext jobCtx) {
        executor.execute(() -> processAsync(keyword, jobCtx));
    }

    private void processAsync(String keyword, FallbackJobContext jobCtx) {
        long startedAt = System.currentTimeMillis();
        try {
            // 비동기 워커는 servlet ctx와 무관 — Google 자체 timeout(1500ms) + 약간 여유의 데드라인 적용.
            ChatbotContext subCtx = ChatbotContext.start(ASYNC_DEADLINE_MS);
            List<PlaceSearchHit> hits = googlePlacesClient.searchByKeyword(keyword, MAX_MULTIPLE, subCtx);
            PlaceSearchOutcome outcome = toOutcome(hits);
            handleAsyncOutcome(jobCtx, outcome);
        } catch (Exception e) {
            log.error("Google Places async fallback failed keyword={} cause={}", keyword, e.getMessage());
            slackNotifier.notifyFailure("Google Places async fallback failed",
                    buildContext(keyword, jobCtx, e));
            kakaoCallbackClient.pushText(jobCtx.callbackUrl(), "장소를 찾을 수 없습니다.");
        } finally {
            log.debug("place fallback async done keyword={} durationMs={}",
                    keyword, System.currentTimeMillis() - startedAt);
        }
    }

    private void handleAsyncOutcome(FallbackJobContext jobCtx, PlaceSearchOutcome outcome) {
        if (outcome instanceof PlaceSearchOutcome.Single single) {
            try {
                Pin saved = pinService.registerFromInstagram(
                        jobCtx.userId(), jobCtx.groupId(), single.hit(), jobCtx.instagramUrl());
                twoSecondMemoSession.put(jobCtx.botUserKey(), saved.getId());
                kakaoCallbackClient.pushText(jobCtx.callbackUrl(),
                        "장소가 저장되었어요: " + saved.getPlaceName());
            } catch (DataIntegrityViolationException dup) {
                log.debug("async duplicate pin groupId={}", jobCtx.groupId());
                kakaoCallbackClient.pushText(jobCtx.callbackUrl(), "이미 저장된 장소예요.");
            } catch (RuntimeException pinFail) {
                log.error("async pin register failed keyword={} cause={}",
                        jobCtx.keyword(), pinFail.getClass().getSimpleName());
                slackNotifier.notifyFailure("Async pin register failed",
                        buildContext(jobCtx.keyword(), jobCtx, pinFail));
                kakaoCallbackClient.pushText(jobCtx.callbackUrl(), "장소를 찾을 수 없습니다.");
            }
            return;
        }
        if (outcome instanceof PlaceSearchOutcome.Multiple multiple) {
            ChatbotV1Dto.SkillResponse card = PlaceCardBuilder.buildMultipleCard(
                    jobCtx.botUserKey(), multiple.hits(), jobCtx.instagramUrl(), candidateStore);
            kakaoCallbackClient.push(jobCtx.callbackUrl(), card);
            return;
        }
        kakaoCallbackClient.pushText(jobCtx.callbackUrl(), "장소를 찾을 수 없습니다.");
    }

    private PlaceSearchOutcome toOutcome(List<PlaceSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new PlaceSearchOutcome.Empty();
        }
        if (hits.size() == 1) {
            return new PlaceSearchOutcome.Single(hits.get(0));
        }
        List<PlaceSearchHit> trimmed = hits.size() > MAX_MULTIPLE
                ? hits.subList(0, MAX_MULTIPLE)
                : hits;
        return new PlaceSearchOutcome.Multiple(List.copyOf(trimmed));
    }

    private Map<String, Object> buildContext(String keyword, FallbackJobContext jobCtx, Throwable e) {
        Map<String, Object> ctxMap = new LinkedHashMap<>();
        ctxMap.put("keyword", keyword);
        if (jobCtx != null) {
            ctxMap.put("userId", jobCtx.userId());
            ctxMap.put("groupId", jobCtx.groupId());
            ctxMap.put("instagramUrl", jobCtx.instagramUrl());
        }
        ctxMap.put("error", buildErrorSummary(e));
        return ctxMap;
    }

    /**
     * Slack 등 외부 알림 채널 노출용 예외 요약.
     * 내부 SQL/DB 메시지가 노출되지 않도록 클래스명 + CoreException ErrorType만 포함한다.
     */
    private static String buildErrorSummary(Throwable e) {
        String base = e.getClass().getSimpleName();
        if (e instanceof CoreException ce) {
            return base + "(" + ce.getErrorType().getCode() + ")";
        }
        return base;
    }
}
