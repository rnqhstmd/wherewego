package com.wherewego.interfaces.api.group;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Group V1 API", description = "그룹 생성/초대/탈퇴 API 입니다 (Phase 3).")
public interface GroupV1ApiSpec {

    @Operation(
            summary = "그룹 생성",
            description = "로그인 사용자가 새 그룹을 생성하고 첫 활성 멤버가 됩니다 (FR-GRP-1). " +
                    "1인 1활성 그룹 제약(BR-1)이 적용됩니다."
    )
    ApiResponse<GroupV1Dto.GroupCreatedResponse> createGroup(
            @Parameter(hidden = true) Long userId,
            GroupV1Dto.CreateGroupRequest request
    );

    @Operation(
            summary = "초대 링크 발급",
            description = "활성 멤버가 24시간 TTL 초대 링크를 발급합니다 (FR-GRP-2). " +
                    "재발급 시 동일 그룹의 미수락 토큰은 일괄 만료됩니다 (BR-3)."
    )
    ApiResponse<GroupV1Dto.InviteLinkResponse> issueInviteLink(
            @Parameter(hidden = true) Long userId,
            Long groupId
    );

    @Operation(
            summary = "초대 링크 수락",
            description = "유효한 토큰을 수락하면 활성 멤버로 추가됩니다 (FR-GRP-3). " +
                    "자기수락/만료/중복 사용/정원 초과는 거부됩니다."
    )
    ApiResponse<GroupV1Dto.InviteAcceptResponse> acceptInviteLink(
            @Parameter(hidden = true) Long userId,
            String token
    );

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
}
