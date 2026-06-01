package com.wherewego.interfaces.api.chat;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Chat V1 API", description = "P2 앱 채팅 REST API. 봇 방(인스타 링크→장소 추출) 1턴 전송 및 "
        + "커플 방(1:1) 메시지 전송과 cursor 기반 메시지 페이지 조회를 제공합니다 (FR-4/5/8/9).")
public interface ChatV1ApiSpec {

    @Operation(
            summary = "봇 방 메시지 전송",
            description = "봇 방에 사용자 텍스트(인스타 URL 후보)를 전송합니다 (FR-4, BR-4). "
                    + "활성 봇 방이 없으면 생성합니다. 즉시 PROCESSING 플레이스홀더 "
                    + "{messageId, kind=PROCESSING} 를 반환하며, 실제 장소 추출 결과는 비동기 처리 후 "
                    + "STOMP/푸시로 전달됩니다."
    )
    ApiResponse<ChatV1Dto.SendMessageResponse> postBotMessage(
            @Parameter(hidden = true) Long userId,
            ChatV1Dto.BotMessageRequest request
    );

    @Operation(
            summary = "봇 방 메시지 목록 조회",
            description = "봇 방 메시지를 최신순(id DESC) cursor 페이지로 반환합니다 (FR-5, AC-3). "
                    + "cursor 는 이전 응답의 nextCursor(=마지막 메시지 id) 이며, 미전달 시 최신부터 조회합니다. "
                    + "limit 기본 20, 최대 50(초과 시 50으로 클램프). "
                    + "활성 봇 방이 없으면 빈 페이지 {messages:[], hasMore:false, nextCursor:null} 를 반환합니다."
    )
    ApiResponse<ChatV1Dto.MessagesResponse> getBotMessages(
            @Parameter(hidden = true) Long userId,
            Long cursor,
            Integer limit
    );

    @Operation(
            summary = "커플 방 메시지 전송",
            description = "커플 방(1:1)에 사용자 텍스트를 전송하고 저장합니다 (FR-8, FR-10). "
                    + "활성 멤버가 아니면 GROUP_NOT_MEMBER (403). 상대 멤버가 있으면 커밋 후 STOMP/푸시로 "
                    + "전달됩니다. {messageId, kind} 를 반환합니다."
    )
    ApiResponse<ChatV1Dto.SendMessageResponse> postCoupleMessage(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            ChatV1Dto.CoupleMessageRequest request
    );

    @Operation(
            summary = "커플 방 메시지 목록 조회",
            description = "커플 방 메시지를 최신순(id DESC) cursor 페이지로 반환합니다 (FR-9, AC-3). "
                    + "조회에도 활성 멤버십을 검증하여 비멤버는 GROUP_NOT_MEMBER (403) 로 거부합니다(타 그룹 차단). "
                    + "cursor 는 이전 응답의 nextCursor 이며, 미전달 시 최신부터 조회합니다. "
                    + "limit 기본 20, 최대 50(초과 시 50으로 클램프). "
                    + "활성 커플 방이 없으면 빈 페이지를 반환합니다."
    )
    ApiResponse<ChatV1Dto.MessagesResponse> getCoupleMessages(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            Long cursor,
            Integer limit
    );
}
