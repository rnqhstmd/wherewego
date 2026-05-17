package com.wherewego.domain.pin;

import com.wherewego.domain.BaseEntity;
import com.wherewego.domain.place.PlaceSearchHit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.math.BigDecimal;

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
                name = "uq_pins_group_instagram",
                columnNames = {"group_id", "instagram_url"}
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

    @Enumerated(EnumType.STRING)
    @Column(name = "tag", nullable = false, length = 10)
    private PinTag tag;

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
     * tag=PLACE, memoSource=null (2초 룰 메모가 도착해야 AUTO 부착).
     */
    public static Pin autoFromInstagram(Long groupId, Long ownerUserId, PlaceSearchHit hit, String instagramUrl) {
        return new Pin(
                groupId,
                ownerUserId,
                hit.placeName(),
                hit.address(),
                toBigDecimal(hit.latitude()),
                toBigDecimal(hit.longitude()),
                instagramUrl,
                PinTag.PLACE
        );
    }

    /**
     * 사용자가 후보 카드 중 하나를 선택한 결과 기반 등록 핀.
     * autoFromInstagram 과 동일 구조 (tag=PLACE, memoSource=null).
     */
    public static Pin fromSelection(Long groupId, Long ownerUserId, PlaceSearchHit hit, String instagramUrl) {
        return new Pin(
                groupId,
                ownerUserId,
                hit.placeName(),
                hit.address(),
                toBigDecimal(hit.latitude()),
                toBigDecimal(hit.longitude()),
                instagramUrl,
                PinTag.PLACE
        );
    }

    /**
     * 사용자가 웹/모바일에서 직접 입력(검색 선택 또는 좌표 picker)한 결과 기반 등록 핀.
     * <p>{@link #autoFromInstagram}/{@link #fromSelection} 과 달리 {@code tag} 를 호출자가 지정한다 (PLACE | MEMORY).
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
        return new Pin(
                groupId,
                userId,
                placeName,
                address,
                latitude,
                longitude,
                instagramUrl,
                tag
        );
    }

    private static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /**
     * 수동 메모 적용. memo 는 non-null, 길이 ≤ 500 자 (호출 전 서비스에서 검증).
     * memoSource 를 MANUAL 로 마킹하여 이후 AUTO 메모 갱신을 차단한다 (BR-3, FR-4).
     */
    public void applyManualMemo(String memo) {
        this.memo = memo;
        this.memoSource = MemoSource.MANUAL;
    }

    /**
     * 메모 제거 + 잠금 해제. memo 와 memoSource 모두 NULL 로 초기화한다 (BR-8).
     * 이후 {@link PinRepository#updateAutoMemoIfNotManual} 의 WHERE 조건이 다시 통과한다.
     */
    public void clearMemo() {
        this.memo = null;
        this.memoSource = null;
    }

    /**
     * 태그 변경. tag 는 non-null (호출 전 서비스에서 검증).
     */
    public void changeTag(PinTag tag) {
        this.tag = tag;
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }
}
