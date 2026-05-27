package com.wherewego.domain.pin.want;

import com.wherewego.domain.group.GroupMemberRepository;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.pin.Pin;
import com.wherewego.domain.pin.PinEvent;
import com.wherewego.domain.pin.PinEventAction;
import com.wherewego.domain.pin.PinEventRepository;
import com.wherewego.domain.pin.PinRepository;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 WANT(관심 표현) 서비스. 토글 트랜잭션·과반 검사·REEL→WISH 전환 이벤트 발행을 단일 책임으로 격리한다.
 *
 * <p><b>Phase 12 범위 제약</b>: PRD 결정에 따라 그룹원 탈퇴/가입 시 want_count 소급 재계산은
 * Phase 12 범위 외이며, <b>2인 MVP 가정</b> 하에서만 안전하다. 본 서비스는 {@code pins} 행만
 * SELECT FOR UPDATE 로 잠그고 {@code group_members} 는 잠그지 않으므로, 3인↑ 그룹에서 활성
 * 멤버 수가 race 로 변동하면 과반 임계에 미세한 오차가 발생할 수 있다. 3인↑ 그룹 지원 시
 * {@code group_members} 잠금 정책(예: 그룹 행 advisory lock 또는 active_count 캐시 컬럼)을
 * 재설계해야 한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WantService {

    private final PinRepository pinRepository;
    private final PinEventRepository pinEventRepository;
    private final GroupMemberService groupMemberService;
    private final GroupMemberRepository groupMemberRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * WANT 토글. 멱등성·동시성·과반 전환을 단일 트랜잭션에 격리한다 (FR-PIN-12-2~6).
     *
     * <p>흐름:
     * <ol>
     *     <li>활성 멤버십 검증 (비활성 → GROUP_NOT_MEMBER 403)</li>
     *     <li>{@code pins} 행 비관 락 조회 (PIN_NOT_FOUND 404)</li>
     *     <li>MEMORY 태그 가드 (PIN_WANT_FORBIDDEN_TAG 400)</li>
     *     <li>{@code pin_events} 존재 여부 확인 → 없으면 INSERT + delta=+1, 있으면 DELETE + delta=-1</li>
     *     <li>{@link Pin#applyWantDelta(int)}</li>
     *     <li>delta=+1 이고 tag=REEL 이면 과반 검사 → {@link WishConvertedEvent} 발행</li>
     * </ol>
     */
    @Transactional
    public WantToggleResult toggle(Long userId, Long groupId, Long pinId) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));
        // NOTE(Phase 12 MVP): group_members 미잠금. 2인 그룹 가정 하에서만 안전.
        //                     3인↑ 확장 시 active_count race 정책 재설계 필요.
        if (pin.getTag() == PinTag.MEMORY) {
            throw new CoreException(ErrorType.PIN_WANT_FORBIDDEN_TAG);
        }

        boolean existed = pinEventRepository.existsByPinAndUserAndAction(pinId, userId, PinEventAction.WANT);
        boolean myWant;
        int delta;
        if (existed) {
            pinEventRepository.deleteByPinAndUserAndAction(pinId, userId, PinEventAction.WANT);
            delta = -1;
            myWant = false;
        } else {
            try {
                pinEventRepository.save(PinEvent.wantOf(pinId, userId, groupId));
            } catch (DataIntegrityViolationException e) {
                // 동시 클릭으로 uq_pin_events_pin_user_want 부분 UNIQUE 충돌 → 멱등 no-op.
                // want_count 는 이미 카운팅되었으므로 증가 건너뜀.
                log.debug("toggle WANT INSERT skipped (duplicate) pinId={} userId={}", pinId, userId);
                return new WantToggleResult(pin.getTag(), pin.getWantCount(), true, false);
            }
            delta = +1;
            myWant = true;
        }
        pin.applyWantDelta(delta);

        boolean wishConverted = false;
        if (delta == +1 && pin.getTag() == PinTag.REEL) {
            int activeMemberCount = (int) groupMemberRepository.countActiveByGroupId(groupId);
            wishConverted = pin.transitionToWishIfMajority(activeMemberCount);
            if (wishConverted) {
                eventPublisher.publishEvent(new WishConvertedEvent(
                        groupId, pinId, userId, pin.getPlaceName()));
            }
        }
        return new WantToggleResult(pin.getTag(), pin.getWantCount(), myWant, wishConverted);
    }

    /**
     * 현재 핀에 대한 사용자의 WANT 상태 조회. 활성 멤버십 검증 + 핀 존재 확인 후
     * {@code want_count} 와 {@code myWant} 를 반환한다.
     *
     * <p>readOnly 트랜잭션에서 SELECT FOR UPDATE 를 사용하면 일부 드라이버/설정에서
     * "cannot use SELECT FOR UPDATE in a read-only transaction" 오류가 발생할 수 있어,
     * 락 없는 단건 조회({@link PinRepository#findActiveByIdAndGroupId})를 사용한다.</p>
     */
    @Transactional(readOnly = true)
    public WantStatusResult getStatus(Long userId, Long groupId, Long pinId) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = pinRepository.findActiveByIdAndGroupId(pinId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));
        boolean myWant = pinEventRepository.existsByPinAndUserAndAction(pinId, userId, PinEventAction.WANT);
        return new WantStatusResult(pin.getWantCount(), myWant);
    }

    /**
     * 챗봇 SINGLE_WANT/MULTI 선택 핀 저장 직후 WANT 1회 적용 전용 헬퍼.
     *
     * <p>{@link #toggle} 과 달리 <b>INSERT-only</b>: 이미 WANT 가 존재하면 no-op. 따라서
     * 카카오 웹훅 재시도/중복 발화로 같은 botUserKey 가 두 번 들어와도 의도치 않은 DELETE
     * 가 발생하지 않는다.</p>
     *
     * <p>과반 검사·WISH 전환·{@link WishConvertedEvent} 발행은 {@link #toggle} 과 동일하게
     * 수행한다 (PRD FR-PIN-12-22). 1인 그룹의 경우 본인 1표로 즉시 WISH 전환되며, fan-out
     * receiver=0 이므로 알림은 발송되지 않고 태그만 전환된다 — 사용자 결정에 따른 의도된 동작.</p>
     *
     * @param activeMemberCount 호출자(챗봇 핸들러)가 사전에 조회한 활성 멤버 수.
     *                          핸들러가 N건 일괄 저장 시 그룹원 수 1회 조회로 재사용 가능.
     */
    @Transactional
    public WantToggleResult markWantOnInitialSave(
            Long userId, Long groupId, Long pinId, int activeMemberCount) {

        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));

        // 챗봇이 막 생성한 REEL 핀에 호출되므로 MEMORY 가드는 사실상 불필요하나 도메인 보호 차원에서 유지.
        if (pin.getTag() == PinTag.MEMORY) {
            throw new CoreException(ErrorType.PIN_WANT_FORBIDDEN_TAG);
        }

        boolean inserted;
        try {
            pinEventRepository.save(PinEvent.wantOf(pinId, userId, groupId));
            inserted = true;
        } catch (DataIntegrityViolationException e) {
            // 이미 동일 (pin_id, user_id, WANT) 가 존재 → 멱등 no-op.
            // want_count 는 이미 카운팅되었으므로 증가 건너뜀.
            log.debug("markWantOnInitialSave skipped (duplicate) pinId={} userId={}", pinId, userId);
            inserted = false;
        }

        boolean wishConverted = false;
        if (inserted) {
            pin.applyWantDelta(+1);
            if (pin.getTag() == PinTag.REEL) {
                wishConverted = pin.transitionToWishIfMajority(activeMemberCount);
                if (wishConverted) {
                    eventPublisher.publishEvent(new WishConvertedEvent(
                            groupId, pinId, userId, pin.getPlaceName()));
                }
            }
        }
        return new WantToggleResult(pin.getTag(), pin.getWantCount(), true, wishConverted);
    }
}
