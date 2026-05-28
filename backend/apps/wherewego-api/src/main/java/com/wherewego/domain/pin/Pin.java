package com.wherewego.domain.pin;

import com.wherewego.domain.BaseEntity;
import com.wherewego.domain.place.PlaceSearchHit;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

/**
 * 그룹 공유 장소 핀. V001 스키마 {@code pins} 테이블 매핑.
 *
 * <p>설계서 §3.3 과 실제 V001 컬럼이 다른 부분(SQL 우선):</p>
 * <ul>
 *     <li>{@code owner_user_id} → V001 {@code created_by}</li>
 *     <li>{@code place_address} → V001 {@code address}</li>
 *     <li>{@code kakao_place_id} : V001 에 컬럼 없음 — 엔티티에서 제외</li>
 *     <li>좌표 컬럼은 V001 {@code DECIMAL(10,7) NOT NULL} → {@link BigDecimal} 매핑</li>
 * </ul>
 */
@Entity
@Getter
@Table(
        name = "pins",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_pins_group_instagram_place",
                columnNames = {"group_id", "instagram_url", "place_name"}
        )
)
public class Pin extends BaseEntity {

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "place_name", nullable = false, length = 200)
    private String placeName;

    @Column(name = "address")
    private String address;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "instagram_url")
    private String instagramUrl;

    @Column(name = "memo")
    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(name = "memo_source", length = 10)
    private MemoSource memoSource;

    @Column(name = "memo_updated_by")
    private Long memoUpdatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "tag", nullable = false, length = 10)
    private PinTag tag;

    /**
     * WISH/REEL → MEMORY 전환 시각 (V010). NULL 허용.
     * <ul>
     *     <li>WISH/REEL 상태: 항상 NULL.</li>
     *     <li>MEMORY 로 처음 전환된 순간 {@link ZonedDateTime#now()} 가 기록된다.</li>
     *     <li>V010 이전에 생성된 기존 MEMORY 핀: NULL (UI 는 createdAt 폴백).</li>
     * </ul>
     */
    @Column(name = "visited_at")
    private ZonedDateTime visitedAt;

    protected Pin() { }

    private Pin(Long groupId,
                Long createdBy,
                String placeName,
                String address,
                BigDecimal latitude,
                BigDecimal longitude,
                String instagramUrl,
                PinTag tag) {
        this.groupId = groupId;
        this.createdBy = createdBy;
        this.placeName = placeName;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.instagramUrl = instagramUrl;
        this.tag = tag;
    }

    /**
     * 인스타그램 링크 단건 결과(Single) 기반 자동 등록 핀.
     * tag=REEL, memoSource=null (2초 룰 메모가 도착해야 AUTO 부착).
     */
    public static Pin autoFromInstagram(Long groupId, Long ownerUserId, PlaceSearchHit hit, String instagramUrl) {
        String normalizedUrl = validateInstagramUrl(instagramUrl);
        return new Pin(
                groupId,
                ownerUserId,
                hit.placeName(),
                hit.address(),
                toBigDecimal(hit.latitude()),
                toBigDecimal(hit.longitude()),
                normalizedUrl,
                PinTag.REEL
        );
    }

    /**
     * 사용자가 후보 카드 중 하나를 선택한 결과 기반 등록 핀.
     * <p>Phase 13: 저장 태그를 호출자가 지정한다. 챗봇 위시 직저장(WISH) / 발견 저장(REEL) 모두 본 팩토리를 사용한다.
     * memoSource 는 null 로 시작하며 2초 룰/AUTO 메모가 도착해야 부착된다.</p>
     */
    public static Pin fromSelection(Long groupId, Long ownerUserId, PlaceSearchHit hit,
                                    String instagramUrl, PinTag tag) {
        String normalizedUrl = validateInstagramUrl(instagramUrl);
        return new Pin(
                groupId,
                ownerUserId,
                hit.placeName(),
                hit.address(),
                toBigDecimal(hit.latitude()),
                toBigDecimal(hit.longitude()),
                normalizedUrl,
                tag
        );
    }

    /**
     * 사용자가 웹/모바일에서 직접 입력(검색 선택 또는 좌표 picker)한 결과 기반 등록 핀.
     * <p>{@link #autoFromInstagram}/{@link #fromSelection} 과 달리 {@code tag} 를 호출자가 지정한다 (WISH | MEMORY).
     * memo 는 별도로 {@link #applyManualMemo(String)} 를 호출하여 MANUAL 마킹과 함께 부착한다.</p>
     */
    public static Pin createFromUser(Long groupId,
                                     Long userId,
                                     String placeName,
                                     String address,
                                     BigDecimal latitude,
                                     BigDecimal longitude,
                                     String instagramUrl,
                                     PinTag tag) {
        String normalizedUrl = validateInstagramUrl(instagramUrl);
        return new Pin(
                groupId,
                userId,
                placeName,
                address,
                latitude,
                longitude,
                normalizedUrl,
                tag
        );
    }

    private static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /**
     * instagramUrl 검증 (XSS 방어) 및 trim 정규화. null/빈 문자열(trim 후 빈)은 null 반환 (선택 필드),
     * 값이 있으면 반드시 {@code https://} 로 시작해야 한다 (javascript:, data: 등 차단).
     * <p>반환된 trim 된 값을 그대로 entity 에 저장하여 선행/후행 공백이 DB 에 새지 않도록 한다
     * (PinCard.startsWith("https://") 검사 일관성 + UNIQUE 우회 차단).</p>
     */
    private static String validateInstagramUrl(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.startsWith("https://")) {
            throw new CoreException(ErrorType.PIN_INSTAGRAM_URL_INVALID);
        }
        return trimmed;
    }

    /**
     * 수동 메모 적용. memo 는 non-null, 길이 ≤ 500 자 (호출 전 서비스에서 검증).
     * memoSource 를 MANUAL 로 마킹하여 이후 AUTO 메모 갱신을 차단한다 (BR-3, FR-4).
     */
    public void applyManualMemo(String memo, Long updatedBy) {
        this.memo = memo;
        this.memoSource = MemoSource.MANUAL;
        this.memoUpdatedBy = updatedBy;
    }

    /**
     * Phase 12: 챗봇 broadcast 메모 적용 (AUTO 마킹). 호출 전 서비스에서 길이 검증.
     * <p>{@link #applyManualMemo} 와 달리 {@code memoUpdatedBy} 는 null 로 유지하여 "시스템 작성" 임을
     * 나타낸다. memoSource=AUTO 이므로 추후 사용자가 직접 메모를 입력하면 MANUAL 로 승격된다.</p>
     */
    public void applyAutoMemo(String memo) {
        this.memo = memo;
        this.memoSource = MemoSource.AUTO;
        this.memoUpdatedBy = null;
    }

    /**
     * 메모 제거 + 잠금 해제. memo, memoSource, memoUpdatedBy 모두 NULL 로 초기화한다 (BR-8).
     * 이후 {@link PinRepository#updateAutoMemoIfNotManual} 의 WHERE 조건이 다시 통과한다.
     */
    public void clearMemo() {
        this.memo = null;
        this.memoSource = null;
        this.memoUpdatedBy = null;
    }

    /**
     * 태그 변경. tag 는 non-null (호출 전 서비스에서 검증).
     *
     * <p>Phase 10 후속: WISH/REEL → MEMORY 전환 시점에 {@link #visitedAt} 을 NOW() 로 기록한다.
     * 다른 전환(예: MEMORY → WISH, WISH ↔ REEL)은 visitedAt 을 건드리지 않는다.
     * 이미 visitedAt 이 있는 핀이 재전환되었다가 다시 MEMORY 가 되어도 새 시점으로 덮어쓴다.</p>
     */
    public void changeTag(PinTag tag) {
        PinTag previous = this.tag;
        this.tag = tag;
        if (tag == PinTag.MEMORY && (previous == PinTag.WISH || previous == PinTag.REEL)) {
            this.visitedAt = ZonedDateTime.now();
        }
    }

    /**
     * 장소 정보 변경 (Phase 2.8). placeName 검증은 Command 레이어에서 수행하므로 도메인은 단순 위임한다.
     * {@code addressProvided=true} 일 때만 address 를 갱신한다 (키 없음 / JSON null / 빈 문자열은 미변경).
     */
    public void changePlaceInfo(String placeName, boolean addressProvided, String address) {
        this.placeName = placeName;
        if (addressProvided) {
            this.address = address;
        }
    }

    /**
     * 좌표 변경 (Phase 2.10 FR-PIN-7). 범위 검증은 Command 레이어에서 수행하므로
     * 도메인은 단순 위임한다. latitude/longitude 는 non-null (호출 전 Command 에서 보장).
     */
    public void changeCoordinate(BigDecimal latitude, BigDecimal longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }
}
