package com.wherewego.interfaces.api.chat;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Chat V1 API", description = "P2 앱 채팅 REST API. 봇 방(인스타 링크→장소 추출) 1턴 전송 및 "
        + "커플 방(1:1) 메시지 전송과 cursor 기반 메시지 페이지 조회를 제공합니다 (FR-4/5/8/9). "
        + "GM-2: 봇 방을 그룹별로 재구성 — DM 목록 + 그룹별 봇 방 전송/조회/읽음(FR-1~FR-7).")
public interface ChatV1ApiSpec {

    @Operation(
            summary = "봇 방 DM 목록 조회 (GM-2)",
            description = "사용자의 활성 그룹별 봇 방 목록을 반환합니다 (FR-2/FR-6, AC-2/AC-6/AC-7). "
                    + "활성 그룹 전부를 가입 순으로 표시하며, 봇 방이 아직 없는 그룹도 가상 항목"
                    + "(roomId/lastPreview/lastSenderType/lastAt=null, unread=false)으로 포함합니다. "
                    + "각 항목은 groupId, groupName, 마지막 메시지 미리보기/발신주체/시각, unread 를 가집니다."
    )
    ApiResponse<List<ChatV1Dto.BotRoomSummaryResponse>> getBotRooms(
            @Parameter(hidden = true) Long userId
    );

    @Operation(
            summary = "그룹별 봇 방 메시지 전송 (GM-2)",
            description = "지정 그룹의 봇 방에 사용자 텍스트(인스타 URL 후보)를 전송합니다 (FR-3, BR-4). "
                    + "활성 멤버가 아니면 GROUP_NOT_MEMBER (403). 활성 봇 방이 없으면 생성합니다. "
                    + "즉시 PROCESSING 플레이스홀더 {messageId, kind=PROCESSING} 를 반환하며, 실제 장소 추출 결과는 "
                    + "비동기 처리 후 푸시로 전달됩니다."
    )
    ApiResponse<ChatV1Dto.SendMessageResponse> postBotGroupMessage(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            ChatV1Dto.BotMessageRequest request
    );

    @Operation(
            summary = "그룹별 봇 방 메시지 목록 조회 (GM-2)",
            description = "지정 그룹 봇 방 메시지를 최신순(id DESC) cursor 페이지로 반환하고 읽음 처리합니다 "
                    + "(FR-4/FR-5, AC-4/AC-5/AC-6). 조회에도 활성 멤버십을 검증하여 비멤버는 GROUP_NOT_MEMBER (403). "
                    + "응답에 groupId 를 포함합니다(iOS 릴스 저장 그룹). cursor 는 이전 응답의 nextCursor 이며, "
                    + "미전달 시 최신부터. limit 기본 20, 최대 50. 활성 봇 방이 없으면 빈 페이지를 반환합니다."
    )
    ApiResponse<ChatV1Dto.MessagesResponse> getBotGroupMessages(
            @Parameter(hidden = true) Long userId,
            Long groupId,
            Long cursor,
            Integer limit
    );

    @Deprecated
    @Operation(
            summary = "봇 방 메시지 전송 (deprecated — GM-2 그룹별 전송으로 대체)",
            description = "[deprecated] groupId 없는 현 iOS 봇 호환용. 최신 활성 그룹의 봇 방으로 폴백하여 전송합니다. "
                    + "활성 그룹이 없으면 GROUP_NOT_MEMBER (403). A단계서 iOS 가 신규 API(POST /bot/{groupId}/messages)로 "
                    + "전환한 뒤 제거됩니다. 동작은 POST /bot/{groupId}/messages 와 동일합니다."
    )
    ApiResponse<ChatV1Dto.SendMessageResponse> postBotMessage(
            @Parameter(hidden = true) Long userId,
            ChatV1Dto.BotMessageRequest request
    );

    @Deprecated
    @Operation(
            summary = "봇 방 메시지 목록 조회 (deprecated — GM-2 그룹별 조회로 대체)",
            description = "[deprecated] groupId 없는 현 iOS 봇 호환용. 최신 활성 그룹의 봇 방을 최신순 cursor 페이지로 "
                    + "반환합니다. 활성 그룹/봇 방이 없으면 빈 페이지. A단계서 신규 API(GET /bot/{groupId}/messages)로 "
                    + "전환한 뒤 제거됩니다. limit 기본 20, 최대 50."
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
