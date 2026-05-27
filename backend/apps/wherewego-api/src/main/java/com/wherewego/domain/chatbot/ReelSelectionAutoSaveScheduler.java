package com.wherewego.domain.chatbot;

import com.wherewego.config.security.RequestIdFilter;
import com.wherewego.domain.bot.BotUserMappingService;
import com.wherewego.domain.chatbot.handler.ReelMemoWaitingHandler;
import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.notification.NotificationService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.HashSet;
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
 * <p>만료 시 처리 (D-3, D-4 보수적):
 * <ul>
 *     <li>SINGLE_WANT  → 전체 REEL 저장 (want 없음)</li>
 *     <li>MULTI_SELECTING → 전체 REEL 저장</li>
 *     <li>BULK_SAVE   → 전체 REEL 저장 (BULK 는 이미 즉시 저장 분기지만 메모 미입력 시 동일)</li>
 *     <li>MEMO_WAITING → 기존 선택대로 저장 (메모 없음)</li>
 * </ul></p>
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
    private final GroupMemberRepository groupMemberRepository;
    private final ReelMemoWaitingHandler reelMemoWaitingHandler;
    private final NotificationService notificationService;
    private final PendingNotificationSession pendingNotificationSession;

    public ReelSelectionAutoSaveScheduler(ReelSavedSelectionSession reelSavedSelectionSession,
                                          BotUserMappingService botUserMappingService,
                                          GroupMemberService groupMemberService,
                                          GroupMemberRepository groupMemberRepository,
                                          ReelMemoWaitingHandler reelMemoWaitingHandler,
                                          NotificationService notificationService,
                                          PendingNotificationSession pendingNotificationSession) {
        this.reelSavedSelectionSession = reelSavedSelectionSession;
        this.botUserMappingService = botUserMappingService;
        this.groupMemberService = groupMemberService;
        this.groupMemberRepository = groupMemberRepository;
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
        Optional<Long> groupIdOpt = groupMemberService.findLatestActiveGroupIdByUserId(userId);
        if (groupIdOpt.isEmpty()) {
            reelSavedSelectionSession.invalidate(botUserKey);
            return;
        }
        Long groupId = groupIdOpt.get();

        // 보수적 처리: 선택이 비어 있으면 전체 인덱스로 채움 (D-3, D-4).
        ReelSavedSelectionSession.Snapshot effective = ensureSelectionFilled(snapshot);
        int activeMemberCount = (int) groupMemberRepository.countActiveByGroupId(groupId);

        try {
            ReelMemoWaitingHandler.SaveResult result = reelMemoWaitingHandler.saveAllSelected(
                    userId, groupId, effective, effective.pendingMemo(), activeMemberCount);
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
            reelSavedSelectionSession.invalidate(botUserKey);
        }
    }

    private static ReelSavedSelectionSession.Snapshot ensureSelectionFilled(
            ReelSavedSelectionSession.Snapshot snapshot) {
        if (!snapshot.selectedIndices().isEmpty()) {
            return snapshot;
        }
        int total = snapshot.places().size();
        HashSet<Integer> all = new HashSet<>();
        for (int i = 1; i <= total; i++) {
            all.add(i);
        }
        return new ReelSavedSelectionSession.Snapshot(
                snapshot.state(),
                snapshot.instagramUrl(),
                snapshot.places(),
                all,
                snapshot.wantOnSelected(),
                snapshot.expiresAt(),
                snapshot.pendingMemo()
        );
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
