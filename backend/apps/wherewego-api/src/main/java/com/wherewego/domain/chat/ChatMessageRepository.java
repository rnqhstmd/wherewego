package com.wherewego.domain.chat;

import java.util.List;
import java.util.Optional;

/**
 * P2: 채팅 메시지 도메인 port. {@code chat_message} 테이블 접근을 노출한다.
 * JPA 어댑터({@code ChatMessageRepositoryAdapter})가 Spring Data 리포지토리를 위임하여 구현한다.
 */
public interface ChatMessageRepository {

    ChatMessage save(ChatMessage message);

    /**
     * 방 메시지를 cursor 기반 최신순(id DESC)으로 조회한다.
     *
     * <p>{@code cursor}가 {@code null}이면 최신부터, non-null이면 {@code id < cursor}만 조회한다.
     * {@code deleted_at IS NULL}만 포함하며, 정확히 {@code limit}개까지 반환한다.
     * hasMore 판정은 호출자(서비스)가 {@code limit + 1}을 넘겨 받아 수행한다.</p>
     */
    List<ChatMessage> findByRoomIdBefore(Long roomId, Long cursor, int limit);

    /**
     * 계정 삭제 시 본인이 발신한 메시지의 {@code sender_user_id}를 NULL 처리한다(PR-3).
     *
     * <p>메시지 자체는 보존하고 발신자 식별만 끊는다. 벌크 갱신이라 {@code updatedAt}도 함께 갱신한다.</p>
     */
    void nullifySenderByUserId(Long userId);

    /**
     * GC-1(FR-GC1-5): 방 소속 활성 메시지 단건 조회 — 온디맨드 추출 대상 검증용.
     * roomId 를 함께 강제하여 타 방 메시지 접근을 차단한다.
     */
    Optional<ChatMessage> findActiveByIdAndRoomId(Long id, Long roomId);
}
