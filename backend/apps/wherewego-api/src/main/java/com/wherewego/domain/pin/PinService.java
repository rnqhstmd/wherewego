package com.wherewego.domain.pin;

import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.domain.user.UserRepository;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PinService {

    private final PinRepository pinRepository;
    private final GroupMemberService groupMemberService;
    private final UserRepository userRepository;

    /** 단건 Pin → PinSummary 변환 (작성자 닉네임 매핑 포함). */
    private PinSummary toSummary(Pin pin) {
        String nickname = userRepository.findById(pin.getCreatedBy())
                .map(u -> u.getNickname())
                .orElse(null);
        return PinSummary.from(pin, nickname);
    }

    /** 다건 Pin → PinSummary 변환. N+1 회피를 위해 createdBy ids 배치 조회. */
    private List<PinSummary> toSummaries(List<Pin> pins) {
        if (pins.isEmpty()) return List.of();
        Set<Long> userIds = pins.stream()
                .map(Pin::getCreatedBy)
                .collect(Collectors.toSet());
        Map<Long, String> nicknames = userRepository.findNicknamesByIds(userIds);
        return pins.stream()
                .map(p -> PinSummary.from(p, nicknames.get(p.getCreatedBy())))
                .toList();
    }

    /**
     * 인스타그램 링크 단건 결과 기반 자동 등록.
     * UNIQUE 충돌 시 {@link org.springframework.dao.DataIntegrityViolationException} 그대로 propagate.
     */
    @Transactional
    public Pin registerFromInstagram(Long userId, Long groupId, PlaceSearchHit hit, String instagramUrl) {
        return registerFromInstagram(userId, groupId, hit, instagramUrl, null);
    }

    /**
     * 인스타그램 링크 단건 결과 기반 자동 등록 + 사용자 메모 포함.
     * memo가 null/blank 이면 메모 없이 저장, 값이 있으면 MANUAL 마킹.
     */
    @Transactional
    public Pin registerFromInstagram(Long userId, Long groupId, PlaceSearchHit hit,
                                     String instagramUrl, String memo) {
        Pin pin = Pin.autoFromInstagram(groupId, userId, hit, instagramUrl);
        if (memo != null && !memo.isBlank()) {
            pin.applyManualMemo(memo);
        }
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
     * 웹/모바일 직접 등록 (Phase 6 FR-API-1).
     * <p>활성 멤버십 검증(BR-1) → 도메인 생성 → memo 가 있으면 MANUAL 마킹 →
     * 저장 시 {@code uq_pins_group_instagram} UNIQUE 충돌은 {@link ErrorType#PLC_DUPLICATE_PIN} 으로 변환한다.</p>
     * <p>중복 검증은 {@code instagramUrl != null} 인 경우에만 동작 (BR-3 — 직접 등록 중복 차단 미적용).</p>
     */
    @Transactional
    public PinSummary addPin(Long userId, Long groupId, PinCreateCommand cmd) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = Pin.createFromUser(
                groupId,
                userId,
                cmd.placeName(),
                cmd.address(),
                cmd.latitude(),
                cmd.longitude(),
                cmd.instagramUrl(),
                cmd.tag()
        );
        if (cmd.memo() != null && !cmd.memo().isBlank()) {
            pin.applyManualMemo(cmd.memo());
        }
        Pin saved;
        try {
            saved = pinRepository.saveAndFlush(pin);
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(ErrorType.PLC_DUPLICATE_PIN);
        }
        return toSummary(saved);
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
        return toSummaries(pins);
    }

    /**
     * 그룹의 활성 핀 목록을 페이지네이션하여 조회한다.
     * 활성 멤버십이 없으면 {@link ErrorType#GROUP_NOT_MEMBER}.
     * {@code hasNext} 는 {@code (long)(page + 1) * size < totalCount} 로 계산하여 오버플로를 방지한다.
     */
    @Transactional(readOnly = true)
    public PinListResult listGroupPinsPaged(Long userId, Long groupId, PinTag tagFilter, int page, int size) {
        groupMemberService.requireActiveMembership(userId, groupId);

        List<Pin> pins;
        long totalCount;
        if (tagFilter == null) {
            pins = pinRepository.findActiveByGroupIdOrderByCreatedAtDesc(groupId, page, size);
            totalCount = pinRepository.countActiveByGroupId(groupId);
        } else {
            pins = pinRepository.findActiveByGroupIdAndTagOrderByCreatedAtDesc(groupId, tagFilter, page, size);
            totalCount = pinRepository.countActiveByGroupIdAndTag(groupId, tagFilter);
        }

        List<PinSummary> items = toSummaries(pins);
        boolean hasNext = (long) (page + 1) * size < totalCount;

        return new PinListResult(items, totalCount, hasNext);
    }

    /**
     * 핀 부분 수정. 활성 멤버십(AC-15) → 비관 락 조회 → memo/tag/placeName/address 독립 갱신(AC-6,7,8).
     * 빈 memo 는 잠금 해제(AC-11/BR-8), 비어있지 않은 memo 는 MANUAL 마킹(AC-10/BR-3).
     * Phase 2.8: placeName/address 도 동일 트랜잭션에서 독립 갱신 가능.
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
            String m = cmd.memo();
            if (m == null || m.isEmpty()) {
                pin.clearMemo();
            } else {
                pin.applyManualMemo(m);
            }
        }
        if (cmd.placeNameProvided()) {
            pin.changePlaceInfo(cmd.placeName(), cmd.addressProvided(), cmd.address());
        } else if (cmd.addressProvided()) {
            pin.changePlaceInfo(pin.getPlaceName(), true, cmd.address());
        }
        if (cmd.coordinateProvided()) {
            pin.changeCoordinate(cmd.latitude(), cmd.longitude());
        }
        return toSummary(pin);
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
