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
     * 멱등 soft delete. BaseEntity.delete() 위임.
     */
    public void markDeleted() {
        delete();
    }
}
