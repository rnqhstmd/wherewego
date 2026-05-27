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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PinService {

    /** Phase 12: GET /pins 정렬 파라미터 — 생성일 내림차순(default). */
    public static final String SORT_CREATED_AT = "created_at";
    /** Phase 12: GET /pins 정렬 파라미터 — want_count 내림차순 (FR-PIN-12-8). */
    public static final String SORT_WANT_COUNT = "want_count";

    private final PinRepository pinRepository;
    private final PinEventRepository pinEventRepository;
    private final GroupMemberService groupMemberService;
    private final UserRepository userRepository;

    /** 단건 Pin → PinSummary 변환 (작성자 닉네임 매핑 포함). WANT 정보 없는 단건 응답용. */
    private PinSummary toSummary(Pin pin) {
        String createdByNickname = userRepository.findById(pin.getCreatedBy())
                .map(u -> u.getNickname())
                .orElse(null);
        String memoUpdatedByNickname = pin.getMemoUpdatedBy() != null
                ? userRepository.findById(pin.getMemoUpdatedBy()).map(u -> u.getNickname()).orElse(null)
                : null;
        return PinSummary.from(pin, createdByNickname, memoUpdatedByNickname);
    }

    /**
     * 다건 Pin → PinSummary 변환. N+1 회피를 위해 관련 user ids + myWant 핀ID 집합을 배치 조회.
     *
     * <p>Phase 12: {@code viewerId} 기준 {@code pin_events} 조회로 {@code myWant} 를 일괄 주입한다.
     * {@code wantCount} 는 {@link Pin#getWantCount()} 컬럼을 그대로 사용한다.</p>
     */
    private List<PinSummary> toSummaries(List<Pin> pins, Long viewerId) {
        if (pins.isEmpty()) return List.of();
        Set<Long> userIds = pins.stream()
                .flatMap(p -> {
                    java.util.stream.Stream.Builder<Long> b = java.util.stream.Stream.builder();
                    b.add(p.getCreatedBy());
                    if (p.getMemoUpdatedBy() != null) b.add(p.getMemoUpdatedBy());
                    return b.build();
                })
                .collect(Collectors.toSet());
        Map<Long, String> nicknames = userRepository.findNicknamesByIds(userIds);

        List<Long> pinIds = pins.stream().map(Pin::getId).toList();
        Set<Long> myWantPinIds = pinEventRepository.findMyWantPinIds(pinIds, viewerId);

        return pins.stream()
                .map(p -> PinSummary.from(
                        p,
                        nicknames.get(p.getCreatedBy()),
                        p.getMemoUpdatedBy() != null ? nicknames.get(p.getMemoUpdatedBy()) : null,
                        p.getWantCount(),
                        myWantPinIds.contains(p.getId())))
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
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = Pin.autoFromInstagram(groupId, userId, hit, instagramUrl);
        if (memo != null && !memo.isBlank()) {
            pin.applyManualMemo(memo, userId);
        }
        return pinRepository.save(pin);
    }

    /**
     * 인스타그램 링크 자동 등록 + 좌표/이름 기반 중복 사전 검사.
     *
     * <p>URL이 달라도 같은 그룹 + 동일 placeName + 좌표 근접(±0.0001도, 약 10m)이면
     * 이미 저장된 핀으로 간주하여 {@link RegisterPinResult#alreadyExisted()}{@code =true} 로 반환한다.
     * 이 경우 새 INSERT 없이 기존 핀을 그대로 돌려준다 (memo 갱신도 하지 않는다).</p>
     *
     * <p>{@link DataIntegrityViolationException} (동일 URL+이름 재시도 등 UNIQUE 충돌) 도
     * 이미 저장된 것으로 간주하여 동일 분기로 처리한다.</p>
     */
    @Transactional
    public RegisterPinResult registerFromInstagramWithDedup(Long userId, Long groupId,
                                                            PlaceSearchHit hit,
                                                            String instagramUrl, String memo) {
        groupMemberService.requireActiveMembership(userId, groupId);
        BigDecimal lat = BigDecimal.valueOf(hit.latitude());
        BigDecimal lng = BigDecimal.valueOf(hit.longitude());
        Optional<Pin> existing = pinRepository.findActiveByGroupPlaceNear(
                groupId, hit.placeName(), lat, lng);
        if (existing.isPresent()) {
            return new RegisterPinResult(existing.get(), true);
        }
        try {
            Pin pin = Pin.autoFromInstagram(groupId, userId, hit, instagramUrl);
            if (memo != null && !memo.isBlank()) {
                pin.applyManualMemo(memo, userId);
            }
            Pin saved = pinRepository.saveAndFlush(pin);
            return new RegisterPinResult(saved, false);
        } catch (DataIntegrityViolationException e) {
            // 좌표/이름 매칭으로 못 잡았지만 (group_id, instagram_url, place_name) UNIQUE 에 걸린 경우.
            // 동일 URL+이름 재시도 등. 이미 저장된 것으로 간주.
            Optional<Pin> retried = pinRepository.findActiveByGroupPlaceNear(
                    groupId, hit.placeName(), lat, lng);
            return retried.map(p -> new RegisterPinResult(p, true))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 후보 카드 선택 기반 등록. UNIQUE 충돌 시 동일하게 propagate.
     */
    @Transactional
    public Pin registerFromSelection(Long userId, Long groupId, PlaceSearchHit hit, String instagramUrl) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = Pin.fromSelection(groupId, userId, hit, instagramUrl);
        return pinRepository.save(pin);
    }

    /**
     * 후보 카드 선택 기반 등록 + 좌표/이름 기반 중복 사전 검사.
     * URL이 달라도 같은 그룹 + 동일 placeName + 좌표 근접(±0.0001도) 이면 이미 저장된 핀으로 간주한다.
     * {@link #registerFromInstagramWithDedup} 와 동일 정책. 다만 도메인 팩토리는 {@link Pin#fromSelection} 을 사용한다.
     */
    @Transactional
    public RegisterPinResult registerFromSelectionWithDedup(Long userId, Long groupId,
                                                            PlaceSearchHit hit, String instagramUrl) {
        groupMemberService.requireActiveMembership(userId, groupId);
        BigDecimal lat = BigDecimal.valueOf(hit.latitude());
        BigDecimal lng = BigDecimal.valueOf(hit.longitude());
        Optional<Pin> existing = pinRepository.findActiveByGroupPlaceNear(
                groupId, hit.placeName(), lat, lng);
        if (existing.isPresent()) {
            return new RegisterPinResult(existing.get(), true);
        }
        try {
            Pin pin = Pin.fromSelection(groupId, userId, hit, instagramUrl);
            Pin saved = pinRepository.saveAndFlush(pin);
            return new RegisterPinResult(saved, false);
        } catch (DataIntegrityViolationException e) {
            Optional<Pin> retried = pinRepository.findActiveByGroupPlaceNear(
                    groupId, hit.placeName(), lat, lng);
            return retried.map(p -> new RegisterPinResult(p, true))
                    .orElseThrow(() -> e);
        }
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
            pin.applyManualMemo(cmd.memo(), userId);
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
     *
     * <p>Phase 12: {@code sort}, {@code interestOnly} 신규 파라미터 (FR-PIN-12-8, 9).
     * legacy 모드(페이지 미적용)에서도 정렬/필터 변형을 허용한다.</p>
     */
    @Transactional(readOnly = true)
    public List<PinSummary> listGroupPins(Long userId, Long groupId, PinTag tagFilter,
                                          String sort, boolean interestOnly) {
        groupMemberService.requireActiveMembership(userId, groupId);
        String normalizedSort = normalizeSort(sort);

        List<Pin> pins;
        if (interestOnly) {
            // 관심 필터: tag 조합 OR 전체 — 페이지 없는 경우 size=Integer.MAX_VALUE 의도이지만,
            // legacy 경로 호환을 위해 page=0, size=Integer.MAX_VALUE 로 호출.
            pins = pinRepository.findActiveByGroupIdInterestOnly(groupId, tagFilter, 0, Integer.MAX_VALUE);
        } else if (SORT_WANT_COUNT.equals(normalizedSort)) {
            pins = tagFilter == null
                    ? pinRepository.findActiveByGroupIdSortedByWantCount(groupId, 0, Integer.MAX_VALUE)
                    : pinRepository.findActiveByGroupIdAndTagSortedByWantCount(
                            groupId, tagFilter, 0, Integer.MAX_VALUE);
        } else {
            pins = tagFilter == null
                    ? pinRepository.findActiveByGroupIdOrderByCreatedAtDesc(groupId)
                    : pinRepository.findActiveByGroupIdAndTagOrderByCreatedAtDesc(groupId, tagFilter);
        }
        return toSummaries(pins, userId);
    }

    /**
     * 그룹의 활성 핀 목록 조회 (legacy 시그니처 — sort=null, interestOnly=false).
     * 기존 호출자(테스트 등) 호환 유지용.
     */
    @Transactional(readOnly = true)
    public List<PinSummary> listGroupPins(Long userId, Long groupId, PinTag tagFilter) {
        return listGroupPins(userId, groupId, tagFilter, null, false);
    }

    /**
     * 그룹의 활성 핀 목록을 페이지네이션하여 조회한다.
     * 활성 멤버십이 없으면 {@link ErrorType#GROUP_NOT_MEMBER}.
     * {@code hasNext} 는 {@code (long)(page + 1) * size < totalCount} 로 계산하여 오버플로를 방지한다.
     *
     * <p>Phase 12: {@code sort}, {@code interestOnly} 신규 파라미터 (FR-PIN-12-8, 9).
     * 분기 우선순위: {@code interestOnly=true} 가 {@code sort} 보다 우선 (관심 필터 적용 후 created_at 기준).</p>
     */
    @Transactional(readOnly = true)
    public PinListResult listGroupPinsPaged(Long userId, Long groupId, PinTag tagFilter,
                                            int page, int size, String sort, boolean interestOnly) {
        groupMemberService.requireActiveMembership(userId, groupId);
        String normalizedSort = normalizeSort(sort);

        List<Pin> pins;
        long totalCount;
        if (interestOnly) {
            pins = pinRepository.findActiveByGroupIdInterestOnly(groupId, tagFilter, page, size);
            totalCount = pinRepository.countActiveByGroupIdInterestOnly(groupId, tagFilter);
        } else if (SORT_WANT_COUNT.equals(normalizedSort)) {
            if (tagFilter == null) {
                pins = pinRepository.findActiveByGroupIdSortedByWantCount(groupId, page, size);
                totalCount = pinRepository.countActiveByGroupId(groupId);
            } else {
                pins = pinRepository.findActiveByGroupIdAndTagSortedByWantCount(groupId, tagFilter, page, size);
                totalCount = pinRepository.countActiveByGroupIdAndTag(groupId, tagFilter);
            }
        } else if (tagFilter == null) {
            pins = pinRepository.findActiveByGroupIdOrderByCreatedAtDesc(groupId, page, size);
            totalCount = pinRepository.countActiveByGroupId(groupId);
        } else {
            pins = pinRepository.findActiveByGroupIdAndTagOrderByCreatedAtDesc(groupId, tagFilter, page, size);
            totalCount = pinRepository.countActiveByGroupIdAndTag(groupId, tagFilter);
        }

        List<PinSummary> items = toSummaries(pins, userId);
        boolean hasNext = (long) (page + 1) * size < totalCount;

        return new PinListResult(items, totalCount, hasNext);
    }

    /**
     * 페이지네이션 조회 legacy 시그니처 (sort=null, interestOnly=false). 기존 호출자 호환 유지용.
     */
    @Transactional(readOnly = true)
    public PinListResult listGroupPinsPaged(Long userId, Long groupId, PinTag tagFilter,
                                            int page, int size) {
        return listGroupPinsPaged(userId, groupId, tagFilter, page, size, null, false);
    }

    /**
     * Phase 12: {@code sort} 파라미터 정규화 + 검증.
     * <p>null/blank → {@link #SORT_CREATED_AT} (default). 알 수 없는 값 → {@link ErrorType#PIN_SORT_PARAM_INVALID}.</p>
     */
    private static String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) return SORT_CREATED_AT;
        if (!SORT_CREATED_AT.equals(sort) && !SORT_WANT_COUNT.equals(sort)) {
            throw new CoreException(ErrorType.PIN_SORT_PARAM_INVALID);
        }
        return sort;
    }

    /**
     * 핀 부분 수정. 활성 멤버십(AC-15) → 비관 락 조회 → memo/tag/placeName/address 독립 갱신(AC-6,7,8).
     * 빈 memo 는 잠금 해제(AC-11/BR-8), 비어있지 않은 memo 는 MANUAL 마킹(AC-10/BR-3).
     * Phase 2.8: placeName/address 도 동일 트랜잭션에서 독립 갱신 가능.
     *
     * <p>Phase 10: 반환 타입을 {@link PinUpdateResult} 로 변경. tag 변경 시 이전 태그를 기억해
     * WISH/REEL → MEMORY 전환 1회를 Controller에 시그널로 전달한다 (VISIT_DETECTED 알림 트리거 판정).</p>
     */
    @Transactional
    public PinUpdateResult updatePin(Long userId, Long groupId, Long pinId, PinUpdateCommand cmd) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));
        PinTag previousTag = pin.getTag();
        if (cmd.tagProvided()) {
            pin.changeTag(cmd.tag());
        }
        if (cmd.memoProvided()) {
            String m = cmd.memo();
            if (m == null || m.isEmpty()) {
                pin.clearMemo();
            } else {
                pin.applyManualMemo(m, userId);
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
        boolean wasWishOrReelToMemory = cmd.tagProvided()
                && cmd.tag() == PinTag.MEMORY
                && (previousTag == PinTag.WISH || previousTag == PinTag.REEL);
        return new PinUpdateResult(toSummary(pin), wasWishOrReelToMemory);
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
