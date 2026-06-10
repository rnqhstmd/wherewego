package com.wherewego.domain.chat;

import java.util.Optional;

/**
 * GC-1: 그룹 방 멤버별 읽음 포인터 도메인 port. {@code chat_room_reads} 테이블 접근을 노출한다.
 * JPA 어댑터({@code ChatRoomReadRepositoryAdapter})가 Spring Data 리포지토리를 위임하여 구현한다.
 *
 * <p>(room, user)당 1행은 V021 UNIQUE 제약과 결합한다 — 동시 insert 충돌은 호출자
 * ({@code GroupChatService})가 optimistic insert + 재조회 폴백으로 처리한다.</p>
 */
public interface ChatRoomReadRepository {

    ChatRoomRead save(ChatRoomRead read);

    Optional<ChatRoomRead> findByRoomIdAndUserId(Long roomId, Long userId);
}
