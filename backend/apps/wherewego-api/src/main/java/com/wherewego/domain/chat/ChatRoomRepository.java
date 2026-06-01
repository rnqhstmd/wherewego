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

    Optional<ChatRoom> findActiveBotRoom(Long ownerUserId);

    Optional<ChatRoom> findActiveCoupleRoom(Long groupId);

    Optional<ChatRoom> findById(Long id);

    /**
     * 계정 삭제 시 본인 소유 봇 방(type=BOT)을 soft delete 한다(PR-3).
     *
     * <p>활성({@code deleted_at IS NULL}) 행만 대상으로 하며, 봇 방만 {@code ownerUserId}를 보유한다.
     * 벌크 갱신이라 {@code updatedAt}도 함께 갱신한다.</p>
     */
    void softDeleteByOwner(Long ownerUserId);
}
