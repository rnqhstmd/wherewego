package com.wherewego.domain.chat;

import com.wherewego.domain.BaseEntity;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * P2: 채팅 메시지. V015 스키마 {@code chat_message} 테이블 매핑.
 *
 * <p>발신 주체(senderType)와 종류(kind) 메타데이터를 가진다. {@code payloadJson} 은 이미 직렬화된 JSON
 * 문자열을 보유하며, JSONB 컬럼으로 매핑된다(Hibernate 6 내장 {@code @JdbcTypeCode(SqlTypes.JSON)}).
 * payload 객체 ↔ JSON 변환은 도메인 밖(ChatMessageAppender)이 담당하므로 엔티티는 String 만 다룬다.</p>
 *
 * <p>{@code senderUserId} 는 USER 발신일 때만 채워지며, 계정 삭제 시 NULL 처리(PR-3)된다.</p>
 */
@Entity
@Getter
@Table(name = "chat_message")
public class ChatMessage extends BaseEntity {

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 20)
    private SenderType senderType;

    @Column(name = "sender_user_id")
    private Long senderUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private MessageKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    protected ChatMessage() { }

    private ChatMessage(Long roomId, SenderType senderType, Long senderUserId,
                        MessageKind kind, String payloadJson) {
        this.roomId = roomId;
        this.senderType = senderType;
        this.senderUserId = senderUserId;
        this.kind = kind;
        this.payloadJson = payloadJson;
        guard();
    }

    @Override
    protected void guard() {
        if (roomId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "roomId는 비어있을 수 없습니다.");
        }
        if (senderType == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "senderType은 비어있을 수 없습니다.");
        }
        if (kind == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "kind는 비어있을 수 없습니다.");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "payloadJson은 비어있을 수 없습니다.");
        }
    }

    /**
     * 단일 팩토리. {@code payloadJson} 은 호출자(ChatMessageAppender)가 직렬화한 JSON 문자열을 받는다.
     */
    public static ChatMessage create(Long roomId, SenderType senderType, Long senderUserId,
                                     MessageKind kind, String payloadJson) {
        return new ChatMessage(roomId, senderType, senderUserId, kind, payloadJson);
    }

    public boolean isActive() {
        return getDeletedAt() == null;
    }
}
