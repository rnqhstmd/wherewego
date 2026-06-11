package com.wherewego.domain.group;

import com.wherewego.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 그룹 엔티티. groups 테이블 매핑 (V001).
 *
 * <p>핀/메모/태그를 공유하는 사용자 묶음. MVP 2인 커플, N인 확장 가능 구조.</p>
 * <p>비즈니스 제약: group_members 활성 멤버 ≤ 2 (BR-8). 그룹 해체는 deletedAt(BaseEntity) soft delete.</p>
 */
@Entity(name = "GroupAggregate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "groups")
public class Group extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * GP-1: 그룹 대표 이미지 원본 S3 객체 키 (V022). NULL = 이미지 없음.
     * 공개 URL 은 서비스에서 S3Properties 와 조합한다(PinService.toPublicUrl 동일 규칙).
     */
    @Column(name = "image_key")
    private String imageKey;

    /** GP-1: 그룹 대표 이미지 썸네일 S3 객체 키 (V022). 원본과 uuid 공유. */
    @Column(name = "image_thumb_key")
    private String imageThumbKey;

    private Group(String name) {
        this.name = name;
    }

    public static Group create(String name) {
        return new Group(name);
    }

    /**
     * 그룹 이름 변경. 검증(trim/길이)은 서비스 레이어에서 수행한 정상 값을 받는다.
     */
    public void rename(String name) {
        this.name = name;
    }

    /**
     * GP-1: 그룹 대표 이미지 키 갱신(업로드/교체). 검증/S3 저장은 서비스가 마친 정상 키를 받는다.
     */
    public void updateImage(String key, String thumbKey) {
        this.imageKey = key;
        this.imageThumbKey = thumbKey;
    }

    /**
     * GP-1: 그룹 대표 이미지 제거 — 두 키를 모두 null 로 비운다(= "이미지 없음" 상태).
     */
    public void clearImage() {
        this.imageKey = null;
        this.imageThumbKey = null;
    }

    /**
     * 멱등 soft delete. BaseEntity.delete() 위임.
     */
    public void markDeleted() {
        delete();
    }
}
