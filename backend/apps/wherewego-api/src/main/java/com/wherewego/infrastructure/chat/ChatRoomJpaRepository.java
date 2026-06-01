package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatRoom;
import com.wherewego.domain.chat.ChatRoomType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatRoomJpaRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findFirstByOwnerUserIdAndTypeAndDeletedAtIsNull(Long ownerUserId, ChatRoomType type);

    Optional<ChatRoom> findFirstByGroupIdAndTypeAndDeletedAtIsNull(Long groupId, ChatRoomType type);
}
