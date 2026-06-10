package com.wherewego.domain.chat;

import java.util.Optional;

/**
 * P2: 채팅 방 도메인 port. {@code chat_room} 테이블 접근을 노출한다.
 * JPA 어댑터({@code ChatRoomRepositoryAdapter})가 Spring Data 리포지토리를 위임하여 구현한다.
 *
 * <p>활성(active)은 {@code deleted_at IS NULL}을 의미한다. 활성 방 1개 강제는
 * V015 부분 UNIQUE 인덱스와 결합한다.</p>
 */
public interface ChatRoomRepository {

    ChatRoom save(ChatRoom room);

    /**
     * 활성 봇 방(type=BOT) 을 owner+group 별로 조회한다(GM-2). 활성 = {@code deleted_at IS NULL}.
     * V020 부분 UNIQUE 인덱스(owner_user_id, group_id)로 (owner, group)당 활성 1개가 보장된다.
     */
    Optional<ChatRoom> findActiveBotRoom(Long ownerUserId, Long groupId);

    /**
     * 활성 그룹 방(type=GROUP)을 그룹별로 조회한다(GC-1). 활성 = {@code deleted_at IS NULL}.
     * V021 부분 UNIQUE 인덱스(group_id)로 그룹당 활성 1개가 보장된다.
     */
    Optional<ChatRoom> findActiveGroupRoom(Long groupId);

    /**
     * 활성 GROUP 방을 race-safe 로 생성한다(없을 때만 — ON CONFLICT DO NOTHING).
     * 동시 생성 충돌에도 예외가 발생하지 않으므로 호출자 트랜잭션이 rollback-only 로 마킹되지 않는다
     * (PR #118 리뷰 반영). 호출 후 {@link #findActiveGroupRoom}으로 재조회한다.
     *
     * @return 삽입 행 수(0 = 이미 존재)
     */
    int insertGroupRoomIfAbsent(Long groupId);

    /**
     * 활성 BOT 방을 race-safe 로 생성한다(없을 때만 — ON CONFLICT DO NOTHING, PR #118 리뷰 반영).
     *
     * @return 삽입 행 수(0 = 이미 존재)
     */
    int insertBotRoomIfAbsent(Long ownerUserId, Long groupId);

    Optional<ChatRoom> findById(Long id);

    /**
     * 계정 삭제 시 본인 소유 봇 방(type=BOT)을 soft delete 한다(PR-3).
     *
     * <p>활성({@code deleted_at IS NULL}) 행만 대상으로 하며, 봇 방만 {@code ownerUserId}를 보유한다.
     * 벌크 갱신이라 {@code updatedAt}도 함께 갱신한다.</p>
     */
    void softDeleteByOwner(Long ownerUserId);

    /**
     * 마지막 1인 탈퇴로 그룹이 soft delete 될 때, 해당 그룹의 그룹 방(type=GROUP)을 함께 soft delete 한다.
     *
     * <p>활성({@code deleted_at IS NULL}) 행만 대상으로 한다. 그룹은 soft delete됐는데 그룹 방이
     * 활성으로 잔존하는 고아 활성 방을 방지한다. 벌크 갱신이라 {@code updatedAt}도 함께 갱신한다.</p>
     */
    void softDeleteByGroup(Long groupId);
}
