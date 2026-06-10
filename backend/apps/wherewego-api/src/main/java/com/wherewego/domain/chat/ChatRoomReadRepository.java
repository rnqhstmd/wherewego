package com.wherewego.domain.chat;

import java.util.Optional;

/**
 * GC-1: 그룹 방 멤버별 읽음 포인터 도메인 port. {@code chat_room_reads} 테이블 접근을 노출한다.
 * JPA 어댑터({@code ChatRoomReadRepositoryAdapter})가 Spring Data 리포지토리를 위임하여 구현한다.
 *
 * <p>(room, user)당 1행은 V021 UNIQUE 제약과 결합한다 — 동시 insert 충돌은
 * {@link #insertIfAbsent}(ON CONFLICT DO NOTHING)가 예외 없이 흡수한다(PR #118 리뷰 반영).</p>
 */
public interface ChatRoomReadRepository {

    ChatRoomRead save(ChatRoomRead read);

    Optional<ChatRoomRead> findByRoomIdAndUserId(Long roomId, Long userId);

    /**
     * 읽음 행을 race-safe 로 생성한다(없을 때만 — ON CONFLICT DO NOTHING).
     * 동시 충돌에도 예외가 발생하지 않으므로 호출자 트랜잭션이 rollback-only 로 마킹되지 않는다.
     *
     * @return 삽입 행 수(0 = 이미 존재)
     */
    int insertIfAbsent(Long roomId, Long userId);
}
