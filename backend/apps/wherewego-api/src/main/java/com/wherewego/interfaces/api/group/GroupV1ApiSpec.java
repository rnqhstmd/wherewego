package com.wherewego.interfaces.api.group;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Group V1 API", description = "그룹 생성/초대/탈퇴 API 입니다 (Phase 3).")
public interface GroupV1ApiSpec {

    @Operation(
            summary = "그룹 생성",
            description = "로그인 사용자가 새 그룹을 생성하고 첫 활성 멤버가 됩니다 (FR-GRP-1). " +
                    "GM-1: 1인 다중 활성 그룹을 지원합니다(1인1활성 제약 해제). 동일 그룹 재가입만 GROUP_REJOIN_FORBIDDEN으로 거부됩니다."
    )
    ApiResponse<GroupV1Dto.GroupCreatedResponse> createGroup(
            @Parameter(hidden = true) Long userId,
            GroupV1Dto.CreateGroupRequest request
    );

    @Operation(
            summary = "초대 링크 발급",
            description = "활성 멤버가 7일 TTL 초대 링크를 발급합니다 (FR-GRP-2). " +
                    "응답에는 UUID 토큰 외에 base56 8자 단축 슬러그 와 공유용 shareUrl 이 함께 포함됩니다. " +
                    "재발급 시 동일 그룹의 미수락 토큰은 일괄 만료됩니다 (BR-3)."
    )
    ApiResponse<GroupV1Dto.InviteLinkResponse> issueInviteLink(
            @Parameter(hidden = true) Long userId,
            Long groupId
    );

    @Operation(
            summary = "초대 링크 수락",
            description = "유효한 토큰을 수락하면 활성 멤버로 추가됩니다 (FR-GRP-3). " +
                    "IC-1: 하나의 코드는 TTL(7일) 동안 그룹 정원(10명) 한도 내에서 복수 사용자가 재사용 수락할 수 있습니다. " +
                    "정원 도달은 만료가 아니라 가입 차단이라 코드는 TTL까지 유지됩니다. " +
                    "자기수락(INVITE_LINK_SELF_ACCEPT)/만료(INVITE_LINK_EXPIRED)/이미 멤버(GROUP_ALREADY_MEMBER)/정원 초과(GROUP_CAPACITY_EXCEEDED)는 거부됩니다. " +
                    "요청(토큰 경로변수)·응답({groupId, acceptedAt}) 구조는 유지됩니다 (BC)."
    )
    ApiResponse<GroupV1Dto.InviteAcceptResponse> acceptInviteLink(
            @Parameter(hidden = true) Long userId,
            String token
    );

    @Operation(
            summary = "초대 링크 미리보기 (공개)",
            description = "단축 슬러그로 그룹명/초대자 닉네임/만료시각을 조회합니다. " +
                    "로그인 전 카톡 미리보기 용. 유효(TTL 미만료 + 정원 미도달)는 200. 만료/존재하지 않음은 404 INVITE_LINK_NOT_FOUND. " +
                    "IC-1(D4): 유효 코드이지만 그룹 정원(10명) 도달이면 409 GROUP_CAPACITY_EXCEEDED 로 구분 응답합니다. " +
                    "IP 기반 분당 30회 레이트리밋이 적용되며, 초과 시 429 INVITE_LINK_RATE_LIMITED."
    )
    ApiResponse<GroupV1Dto.InviteLinkPreviewResponse> previewInviteLinkBySlug(String slug);

    @Operation(
            summary = "그룹 탈퇴",
            description = "활성 멤버가 그룹에서 탈퇴합니다 (FR-GRP-5). " +
                    "마지막 멤버가 탈퇴하면 그룹은 soft delete 처리됩니다."
    )
    ApiResponse<Object> leaveGroup(
            @Parameter(hidden = true) Long userId,
            Long groupId
    );

    @Operation(
            summary = "내 활성 그룹 조회",
            description = "현재 로그인 사용자의 활성 그룹 정보를 반환합니다. 활성 그룹이 없으면 data 는 null 입니다."
    )
    ApiResponse<GroupV1Dto.ActiveGroupResponse> findMyActiveGroup(
            @Parameter(hidden = true) Long userId
    );

    @Operation(
            summary = "내 그룹 목록 조회",
            description = "현재 로그인 사용자의 활성 그룹 목록을 가입 순으로 반환합니다 (GM-1). " +
                    "활성 그룹이 없으면 data 는 빈 배열입니다."
    )
    ApiResponse<List<GroupV1Dto.GroupSummaryResponse>> listMyGroups(
            @Parameter(hidden = true) Long userId
    );
}
