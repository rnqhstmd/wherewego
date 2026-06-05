package com.wherewego.domain.chatbot;

import com.wherewego.config.security.RequestIdFilter;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.handler.ReelMemoWaitingHandler;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.notification.NotificationService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Phase 12 릴스 선택 세션 TTL 만료 시 보수적 자동 저장 스케줄러.
 *
 * <p>{@link ReelSavedSelectionSession} TTL(기본 180초) 만료 시 만료 핸들러 trigger 가 필요하다.
 * Caffeine 자체는 expire 시점에만 evict 하므로, 본 스케줄러가 별도로 동일 시각에 자동 저장을 수행한다.</p>
 *
 * <p>botUserKey 단위로 단일 task. 동일 키에 schedule 재호출 시 이전 task 를 cancel(false) 후 새 task 등록.</p>
 *
 * <p>만료 시 처리 (Phase 13 §4.8, D-3/D-4 보수적): 모든 추출 핀을 저장하되 wishIndices 가 든 것만 WISH.
 * 미응답 단계(MULTI_SELECTING)는 wishIndices 가 비어 있으므로 전체 발견(REEL)으로 저장된다.
 * MEMO_WAITING 단계라면 사용자가 이미 결정한 wishIndices 를 존중한다.</p>
 */
@Component
public class ReelSelectionAutoSaveScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReelSelectionAutoSaveScheduler.class);

    private static final String NOTICE_PREFIX = "📌 응답 시간이 지나 자동 저장되었어요\n";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "reel-selection-auto-save-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final ConcurrentHashMap<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    private final ReelSavedSelectionSession reelSavedSelectionSession;
    private final BotUserMappingService botUserMappingService;
    private final GroupMemberService groupMemberService;
    private final ReelMemoWaitingHandler reelMemoWaitingHandler;
    private final NotificationService notificationService;
    private final PendingNotificationSession pendingNotificationSession;

    public ReelSelectionAutoSaveScheduler(ReelSavedSelectionSession reelSavedSelectionSession,
                                          BotUserMappingService botUserMappingService,
                                          GroupMemberService groupMemberService,
                                          ReelMemoWaitingHandler reelMemoWaitingHandler,
                                          NotificationService notificationService,
                                          PendingNotificationSession pendingNotificationSession) {
        this.reelSavedSelectionSession = reelSavedSelectionSession;
        this.botUserMappingService = botUserMappingService;
        this.groupMemberService = groupMemberService;
        this.reelMemoWaitingHandler = reelMemoWaitingHandler;
        this.notificationService = notificationService;
        this.pendingNotificationSession = pendingNotificationSession;
    }

    /**
     * botUserKey 에 대해 delayMs 후 만료 자동 저장 task 등록. 같은 키 재등록 시 이전 task cancel(false).
     */
    public void schedule(String botUserKey, long delayMs) {
        ScheduledFuture<?> prev = tasks.remove(botUserKey);
        if (prev != null) {
            prev.cancel(false);
        }
        ScheduledFuture<?> next = scheduler.schedule(() -> {
            MDC.put(RequestIdFilter.MDC_KEY, "SCHEDULER");
            try {
                forceSaveOnExpire(botUserKey);
            } catch (RuntimeException e) {
                log.error("Reel auto-save task failed botUserKey={} cause={}",
                        botUserKey, e.getMessage(), e);
            } finally {
                tasks.remove(botUserKey);
                MDC.clear();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        tasks.put(botUserKey, next);
    }

    /** 등록된 task 가 있으면 cancel(false). 사용자가 TTL 내에 응답한 시점에 호출한다. */
    public void cancel(String botUserKey) {
        ScheduledFuture<?> f = tasks.remove(botUserKey);
        if (f != null) {
            f.cancel(false);
        }
    }

    /**
     * TTL 만료 시 보수적으로 현재 세션의 인덱스(또는 전체)로 저장하고 알림 prepend 를 적재한다.
     */
    void forceSaveOnExpire(String botUserKey) {
        Optional<ReelSavedSelectionSession.Snapshot> snapshotOpt =
                reelSavedSelectionSession.peek(botUserKey);
        if (snapshotOpt.isEmpty()) {
            log.info("forceSaveOnExpire skipped (no session) botUserKey={}", botUserKey);
            return;
        }
        ReelSavedSelectionSession.Snapshot snapshot = snapshotOpt.get();

        Optional<Long> userIdOpt = botUserMappingService.resolveUserId(botUserKey);
        if (userIdOpt.isEmpty()) {
            reelSavedSelectionSession.invalidate(botUserKey);
            return;
        }
        Long userId = userIdOpt.get();
        // TODO(GM-2): 그룹 선택 이관 예정. GM-1 전환기=최신 활성 1개 저장(단수 유지, BR-5).
        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            reelSavedSelectionSession.invalidate(botUserKey);
            return;
        }
        Long groupId = groupIdOpt.get();

        // Phase 13: saveAll 이 전체 핀을 저장하고 wishIndices 만 WISH 로 분기한다. 미응답 만료 시
        // wishIndices 가 비어 있으면 전체 발견(REEL)으로 보수 저장된다 (§4.8).
        try {
            ReelMemoWaitingHandler.SaveResult result = reelMemoWaitingHandler.saveAll(
                    userId, groupId, snapshot, snapshot.pendingMemo());
            if (!result.savedPinIds().isEmpty()) {
                try {
                    notificationService.createForChatbotBatch(groupId, userId, result.savedPinIds());
                } catch (RuntimeException e) {
                    log.warn("notification (reel auto-save) failed groupId={} pinCount={}",
                            groupId, result.savedPinIds().size(), e);
                }
                pendingNotificationSession.put(botUserKey,
                        NOTICE_PREFIX + result.savedPinIds().size() + "곳이 저장되었어요");
                log.info("Reel auto-save completed botUserKey={} saved={}",
                        botUserKey, result.savedPinIds().size());
            }
        } finally {
            // PR #76 Gemini #1: TTL 만료 처리 중 동일 botUserKey 로 새 인스타 URL 이 들어와
            // 새 세션이 생성되었을 수 있다. 무조건 invalidate 하면 그 새 세션을 삭제하므로,
            // 현재 캐시의 instagramUrl 이 본 task 가 다룬 snapshot 과 동일할 때에만 evict.
            Optional<ReelSavedSelectionSession.Snapshot> currentOpt =
                    reelSavedSelectionSession.peek(botUserKey);
            if (currentOpt.isPresent()
                    && currentOpt.get().instagramUrl().equals(snapshot.instagramUrl())) {
                reelSavedSelectionSession.invalidate(botUserKey);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
