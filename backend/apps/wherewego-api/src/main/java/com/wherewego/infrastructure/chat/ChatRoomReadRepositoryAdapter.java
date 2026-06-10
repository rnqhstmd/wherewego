package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatRoomRead;
import com.wherewego.domain.chat.ChatRoomReadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ChatRoomReadRepositoryAdapter implements ChatRoomReadRepository {

    private final ChatRoomReadJpaRepository chatRoomReadJpa;

    @Override
    public ChatRoomRead save(ChatRoomRead read) {
        return chatRoomReadJpa.save(read);
    }

    @Override
    public Optional<ChatRoomRead> findByRoomIdAndUserId(Long roomId, Long userId) {
        return chatRoomReadJpa.findFirstByRoomIdAndUserId(roomId, userId);
    }
}
