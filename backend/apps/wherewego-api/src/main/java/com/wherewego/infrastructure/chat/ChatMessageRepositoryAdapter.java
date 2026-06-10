package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatMessage;
import com.wherewego.domain.chat.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryAdapter implements ChatMessageRepository {

    private final ChatMessageJpaRepository chatMessageJpa;

    @Override
    public ChatMessage save(ChatMessage message) {
        return chatMessageJpa.save(message);
    }

    @Override
    public List<ChatMessage> findByRoomIdBefore(Long roomId, Long cursor, int limit) {
        return chatMessageJpa.findByRoomIdBeforeCursor(roomId, cursor, PageRequest.of(0, limit));
    }

    @Override
    public void nullifySenderByUserId(Long userId) {
        chatMessageJpa.nullifySenderByUserId(userId, ZonedDateTime.now());
    }

    @Override
    public Optional<ChatMessage> findActiveByIdAndRoomId(Long id, Long roomId) {
        return chatMessageJpa.findByIdAndRoomIdAndDeletedAtIsNull(id, roomId);
    }

    @Override
    public Optional<ChatMessage> findActiveById(Long id) {
        return chatMessageJpa.findByIdAndDeletedAtIsNull(id);
    }
}
