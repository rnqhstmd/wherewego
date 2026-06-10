package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatRoomRead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatRoomReadJpaRepository extends JpaRepository<ChatRoomRead, Long> {

    Optional<ChatRoomRead> findFirstByRoomIdAndUserId(Long roomId, Long userId);
}
