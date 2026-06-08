package com.wherewego.infrastructure.chat;

import com.wherewego.domain.chat.ChatRoom;
import com.wherewego.domain.chat.ChatRoomRepository;
import com.wherewego.domain.chat.ChatRoomType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ChatRoomRepositoryAdapter implements ChatRoomRepository {

    private final ChatRoomJpaRepository chatRoomJpa;

    @Override
    public ChatRoom save(ChatRoom room) {
        return chatRoomJpa.save(room);
    }

    @Override
    public Optional<ChatRoom> findActiveBotRoom(Long ownerUserId, Long groupId) {
        return chatRoomJpa.findFirstByOwnerUserIdAndGroupIdAndTypeAndDeletedAtIsNull(
                ownerUserId, groupId, ChatRoomType.BOT);
    }

    @Override
    public Optional<ChatRoom> findActiveCoupleRoom(Long groupId) {
        return chatRoomJpa.findFirstByGroupIdAndTypeAndDeletedAtIsNull(groupId, ChatRoomType.COUPLE);
    }

    @Override
    public Optional<ChatRoom> findById(Long id) {
        return chatRoomJpa.findById(id);
    }

    @Override
    public void softDeleteByOwner(Long ownerUserId) {
        chatRoomJpa.softDeleteByOwner(ownerUserId, ChatRoomType.BOT, ZonedDateTime.now());
    }

    @Override
    public void softDeleteByGroup(Long groupId) {
        chatRoomJpa.softDeleteByGroup(groupId, ChatRoomType.COUPLE, ZonedDateTime.now());
    }
}
