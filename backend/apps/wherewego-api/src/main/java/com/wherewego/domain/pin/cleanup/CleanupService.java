package com.wherewego.domain.pin.cleanup;

import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinSummary;
import com.wherewego.domain.user.UserModel;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Phase 12 오래된 핀 정리 서비스 (FR-PIN-12-23~25).
 *
 * <p>정리 대상: {@code tag=REEL AND memo_source='AUTO' AND created_at < NOW()-30일
 * AND deleted_at IS NULL}. 사용자가 7일 snooze 를 누르면 {@code users.cleanup_snoozed_until} 갱신.</p>
 *
 * <p>책임 격리: {@link com.wherewego.domain.pin.PinService} (CRUD) 와 별도 패키지
 * {@code domain.pin.cleanup} 으로 분리한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupService {

    /** Phase 12: 정리 후보 임계 (FR-PIN-12-23) — created_at 이 NOW()-30일 이전. */
    private static final Duration CLEANUP_THRESHOLD = Duration.ofDays(30);

    /** Phase 12: snooze 기본 기간 (FR-PIN-12-25) — 7일. */
    private static final Duration SNOOZE_DURATION = Duration.ofDays(7);

    private final PinRepository pinRepository;
    private final GroupMemberService groupMemberService;
    private final UserRepository userRepository;

    /**
     * 정리 후보 목록 조회 (FR-PIN-12-23).
     *
     * <p>흐름:
     * <ol>
     *     <li>활성 멤버십 검증 (GROUP_NOT_MEMBER 403)</li>
     *     <li>사용자 snooze 상태 확인 → snooze 중이면 빈 목록 + snoozedUntil 반환</li>
     *     <li>그 외엔 {@code pin_repository.findCleanupCandidates} 호출</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public CleanupCandidatesResult listCandidates(Long userId, Long groupId) {
        groupMemberService.requireActiveMembership(userId, groupId);
        ZonedDateTime now = ZonedDateTime.now();

        UserModel user = userRepository.findById(userId)
                .orElseThrow(() -> new CoreException(ErrorType.AUTH_USER_NOT_FOUND));
        if (user.isCleanupSnoozed(now)) {
            return new CleanupCandidatesResult(0, user.getCleanupSnoozedUntil(), List.of());
        }

        ZonedDateTime threshold = now.minus(CLEANUP_THRESHOLD);
        List<Pin> candidates = pinRepository.findCleanupCandidates(groupId, threshold);
        List<PinSummary> items = candidates.stream()
                .map(p -> PinSummary.from(p, null, null))
                .toList();
        return new CleanupCandidatesResult(items.size(), null, items);
    }

    /**
     * 일괄 정리 실행 (FR-PIN-12-24). race-safe: 트랜잭션 내에서 후보 ID 를 재조회하여
     * {@link PinRepository#softDeleteAll(java.util.Collection)} 으로 멱등 soft-delete.
     *
     * @return 이번 호출이 실제로 삭제한 핀 수 (이미 삭제됐던 행 제외)
     */
    @Transactional
    public int executeBulk(Long userId, Long groupId) {
        groupMemberService.requireActiveMembership(userId, groupId);
        ZonedDateTime threshold = ZonedDateTime.now().minus(CLEANUP_THRESHOLD);
        List<Long> pinIds = pinRepository.findCleanupCandidates(groupId, threshold).stream()
                .map(Pin::getId)
                .toList();
        if (pinIds.isEmpty()) {
            return 0;
        }
        return pinRepository.softDeleteAll(pinIds);
    }

    /**
     * 정리 배너 7일 snooze (FR-PIN-12-25). 기존 값이 있어도 NOW()+7일로 덮어쓴다 (재snooze 가능).
     *
     * @return 갱신된 {@code cleanup_snoozed_until}
     */
    @Transactional
    public ZonedDateTime snooze7Days(Long userId) {
        UserModel user = userRepository.findById(userId)
                .orElseThrow(() -> new CoreException(ErrorType.AUTH_USER_NOT_FOUND));
        user.snoozeCleanup(SNOOZE_DURATION);
        userRepository.save(user);
        return user.getCleanupSnoozedUntil();
    }
}
