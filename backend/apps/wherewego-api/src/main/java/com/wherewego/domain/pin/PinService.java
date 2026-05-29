package com.wherewego.domain.pin;

import com.wherewego.config.env.S3Properties;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.domain.pin.PinPhotoStorage.StoredPhoto;
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

    private final PinRepository pinRepository;
    private final GroupMemberService groupMemberService;
    private final UserRepository userRepository;
    private final PinPhotoStorage pinPhotoStorage;
    private final S3Properties s3Properties;

    /** 단건 Pin → PinSummary 변환 (작성자 닉네임 매핑 + 사진 URL 조합 포함). */
    private PinSummary toSummary(Pin pin) {
        String createdByNickname = userRepository.findById(pin.getCreatedBy())
                .map(u -> u.getNickname())
                .orElse(null);
        String memoUpdatedByNickname = pin.getMemoUpdatedBy() != null
                ? userRepository.findById(pin.getMemoUpdatedBy()).map(u -> u.getNickname()).orElse(null)
                : null;
        return PinSummary.from(pin, createdByNickname, memoUpdatedByNickname,
                toPublicUrl(pin.getPhotoKey()), toPublicUrl(pin.getPhotoThumbnailKey()));
    }

    /**
     * S3 객체 키 → 공개 URL 조합 (Phase 13). 키가 없으면 null.
     * <p>tag 무관하게 키가 있으면 조합한다(단순성). MEMORY 게이트는 프론트 UI 책임이다(BR-3 일관).</p>
     */
    private String toPublicUrl(String key) {
        if (key == null) return null;
        // publicBaseUrl 끝 슬래시를 제거해 "//" 이중 슬래시 broken URL 방지.
        String base = s3Properties.publicBaseUrl().replaceAll("/+$", "");
        return base + "/" + key;
    }

    /**
     * 다건 Pin → PinSummary 변환. N+1 회피를 위해 관련 user ids 를 배치 조회하여 닉네임만 주입한다.
     */
    private List<PinSummary> toSummaries(List<Pin> pins) {
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

        return pins.stream()
                .map(p -> PinSummary.from(
                        p,
                        nicknames.get(p.getCreatedBy()),
                        p.getMemoUpdatedBy() != null ? nicknames.get(p.getMemoUpdatedBy()) : null,
                        toPublicUrl(p.getPhotoKey()),
                        toPublicUrl(p.getPhotoThumbnailKey())))
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
     * 후보 카드 선택 기반 등록 (REEL). UNIQUE 충돌 시 동일하게 propagate.
     */
    @Transactional
    public Pin registerFromSelection(Long userId, Long groupId, PlaceSearchHit hit, String instagramUrl) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = Pin.fromSelection(groupId, userId, hit, instagramUrl, PinTag.REEL);
        return pinRepository.save(pin);
    }

    /**
     * 후보 카드 선택 기반 등록 + 좌표/이름 기반 중복 사전 검사.
     * URL이 달라도 같은 그룹 + 동일 placeName + 좌표 근접(±0.0001도) 이면 이미 저장된 핀으로 간주한다.
     * {@link #registerFromInstagramWithDedup} 와 동일 정책. 다만 도메인 팩토리는 {@link Pin#fromSelection} 을 사용한다.
     *
     * <p>Phase 13: 저장 태그를 호출자가 지정한다. 챗봇이 위시 직저장(WISH) / 발견 저장(REEL) 을 구분한다.
     * dedup 으로 잡힌 기존 핀은 태그를 변경하지 않고 {@code alreadyExisted=true} 로 그대로 반환한다
     * (이미 저장된 의사 존중).</p>
     */
    @Transactional
    public RegisterPinResult registerFromSelectionWithDedup(Long userId, Long groupId,
                                                            PlaceSearchHit hit, String instagramUrl,
                                                            PinTag tag) {
        groupMemberService.requireActiveMembership(userId, groupId);
        BigDecimal lat = BigDecimal.valueOf(hit.latitude());
        BigDecimal lng = BigDecimal.valueOf(hit.longitude());
        Optional<Pin> existing = pinRepository.findActiveByGroupPlaceNear(
                groupId, hit.placeName(), lat, lng);
        if (existing.isPresent()) {
            return new RegisterPinResult(existing.get(), true);
        }
        try {
            Pin pin = Pin.fromSelection(groupId, userId, hit, instagramUrl, tag);
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
     * 그룹의 활성 핀 목록을 created_at 내림차순으로 조회한다 (FR-1, BR-2, BR-10).
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
     * 그룹의 활성 핀 목록을 created_at 내림차순으로 페이지네이션하여 조회한다.
     * 활성 멤버십이 없으면 {@link ErrorType#GROUP_NOT_MEMBER}.
     * {@code hasNext} 는 {@code (long)(page + 1) * size < totalCount} 로 계산하여 오버플로를 방지한다.
     */
    @Transactional(readOnly = true)
    public PinListResult listGroupPinsPaged(Long userId, Long groupId, PinTag tagFilter,
                                            int page, int size) {
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
        // 소프트 삭제 시 S3 사진 객체도 best-effort 회수 (공개+immutable 버킷 특성상
        // 삭제된 사진이 URL로 영구 접근되는 것을 방지). 실패해도 예외 전파 안 함(deleteQuietly).
        if (pin.hasPhoto()) {
            pinPhotoStorage.deleteQuietly(pin.getPhotoKey(), pin.getPhotoThumbnailKey());
        }
        pin.delete();
    }

    /**
     * 추억핀 사진 업로드/교체 (Phase 13 FR-PIN-9b~f).
     * <p>활성 멤버십(BR-4) → 비관 락 조회(없으면 {@link ErrorType#PIN_NOT_FOUND}) →
     * MEMORY 검증(아니면 {@link ErrorType#PIN_PHOTO_NOT_MEMORY}, BR-1/AC-3) → 기존 키 백업 →
     * {@code storage.store}(트랜잭션 내, Q3) → {@code applyPhoto} → 기존 키가 있었으면 best-effort 회수
     * (FR-PIN-10b/AC-10) → 갱신 summary 반환.</p>
     * <p>imageBytes/contentType 은 컨트롤러에서 타입/크기를 검증한 값이며, 픽셀 상한은 어댑터가 검증한다.
     * S3 완전 실패는 어댑터가 {@link ErrorType#PIN_PHOTO_STORAGE_FAILED} 로 래핑하여 전파한다(Q4).</p>
     */
    @Transactional
    public PinSummary uploadPhoto(Long userId, Long groupId, Long pinId,
                                  byte[] imageBytes, String contentType) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));
        if (pin.getTag() != PinTag.MEMORY) {
            throw new CoreException(ErrorType.PIN_PHOTO_NOT_MEMORY);
        }

        String oldPhotoKey = pin.getPhotoKey();
        String oldThumbnailKey = pin.getPhotoThumbnailKey();
        boolean hadPhoto = pin.hasPhoto();

        StoredPhoto stored = pinPhotoStorage.store(groupId, pinId, imageBytes, contentType);
        pin.applyPhoto(stored.photoKey(), stored.thumbnailKey(), userId);

        if (hadPhoto) {
            // 교체: 기존 객체 best-effort 회수 (실패해도 새 사진은 유효, FR-PIN-10b/AC-10).
            pinPhotoStorage.deleteQuietly(oldPhotoKey, oldThumbnailKey);
        }
        return toSummary(pin);
    }

    /**
     * 추억핀 사진 삭제 (Phase 13 FR-PIN-10a/b).
     * <p>활성 멤버십(BR-4) → 비관 락 조회 → 키 백업 → {@code clearPhoto}(4필드 null, AC-9) →
     * S3 2객체 best-effort 삭제 → 갱신 summary 반환(204 아님).</p>
     */
    @Transactional
    public PinSummary deletePhoto(Long userId, Long groupId, Long pinId) {
        groupMemberService.requireActiveMembership(userId, groupId);
        Pin pin = pinRepository.findActiveByIdAndGroupIdForUpdate(pinId, groupId)
                .orElseThrow(() -> new CoreException(ErrorType.PIN_NOT_FOUND));

        String photoKey = pin.getPhotoKey();
        String thumbnailKey = pin.getPhotoThumbnailKey();

        pin.clearPhoto();
        pinPhotoStorage.deleteQuietly(photoKey, thumbnailKey);
        return toSummary(pin);
    }
}
