package com.wherewego.domain.pin;

import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PinService {

    private final PinRepository pinRepository;
    private final GroupMemberService groupMemberService;

    /**
     * 인스타그램 링크 단건 결과 기반 자동 등록.
     * UNIQUE 충돌 시 {@link org.springframework.dao.DataIntegrityViolationException} 그대로 propagate.
     */
    @Transactional
    public Pin registerFromInstagram(Long userId, Long groupId, PlaceSearchHit hit, String instagramUrl) {
        Pin pin = Pin.autoFromInstagram(groupId, userId, hit, instagramUrl);
        return pinRepository.save(pin);
    }

    /**
     * 후보 카드 선택 기반 등록. UNIQUE 충돌 시 동일하게 propagate.
     */
    @Transactional
    public Pin registerFromSelection(Long userId, Long groupId, PlaceSearchHit hit, String instagramUrl) {
        Pin pin = Pin.fromSelection(groupId, userId, hit, instagramUrl);
        return pinRepository.save(pin);
    }

    /**
     * 그룹의 활성 핀 목록을 조회한다 (FR-1, BR-2, BR-10).
     * 활성 멤버십이 없으면 {@link ErrorType#GROUP_NOT_MEMBER} (AC-3).
     */
    @Transactional(readOnly = true)
    public List<PinSummary> listGroupPins(Long userId, Long groupId, PinTag tagFilter) {
        groupMemberService.requireActiveMembership(userId, groupId);
        List<Pin> pins = tagFilter == null
                ? pinRepository.findActiveByGroupIdOrderByCreatedAtDesc(groupId)
                : pinRepository.findActiveByGroupIdAndTagOrderByCreatedAtDesc(groupId, tagFilter);
        return pins.stream().map(PinSummary::from).toList();
    }

    /**
     * 핀 부분 수정. 활성 멤버십(AC-15) → 비관 락 조회 → memo/tag 독립 갱신(AC-6,7,8).
     * 빈 memo 는 잠금 해제(AC-11/BR-8), 비어있지 않은 memo 는 MANUAL 마킹(AC-10/BR-3).
     */
    @Transactional
    public PinSummary updatePin(Long userId, Long groupId, Long pinId, PinUpdateCommand cmd) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));
        if (cmd.tagProvided()) {
            pin.changeTag(cmd.tag());
        }
        if (cmd.memoProvided()) {
            if (cmd.memo().isEmpty()) {
                pin.clearMemo();
            } else {
                pin.applyManualMemo(cmd.memo());
            }
        }
        return PinSummary.from(pin);
    }

    /**
     * 핀 소프트 삭제. 활성 멤버십(AC-18) → 비관 락 조회 → {@code BaseEntity.delete()} 멱등 호출(AC-16).
     * 이미 삭제된 행은 비관 락 조회에서 제외되어 {@link ErrorType#PIN_NOT_FOUND} (AC-17).
     */
    @Transactional
    public void softDeletePin(Long userId, Long groupId, Long pinId) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));
        pin.delete();
    }
}
