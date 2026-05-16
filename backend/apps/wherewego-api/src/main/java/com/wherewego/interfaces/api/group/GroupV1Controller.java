package com.wherewego.interfaces.api.group;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class GroupV1Controller implements GroupV1ApiSpec {

    private final GroupMemberService groupMemberService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<GroupV1Dto.GroupCreatedResponse> createGroup(
            @AuthUser Long userId,
            @RequestBody GroupV1Dto.CreateGroupRequest request
    ) {
        return ApiResponse.success(
                GroupV1Dto.GroupCreatedResponse.from(
                        groupMemberService.createGroup(userId, request.name())));
    }

    @PostMapping("/{groupId}/invite-links")
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<GroupV1Dto.InviteLinkResponse> issueInviteLink(
            @AuthUser Long userId,
            @PathVariable Long groupId
    ) {
        return ApiResponse.success(
                GroupV1Dto.InviteLinkResponse.from(
                        groupMemberService.issueInviteLink(userId, groupId)));
    }

    @PostMapping("/invite-links/{token}/accept")
    @Override
    public ApiResponse<GroupV1Dto.InviteAcceptResponse> acceptInviteLink(
            @AuthUser Long userId,
            @PathVariable String token
    ) {
        return ApiResponse.success(
                GroupV1Dto.InviteAcceptResponse.from(
                        groupMemberService.acceptInviteLink(userId, token)));
    }

    @DeleteMapping("/{groupId}/members/me")
    @Override
    public ApiResponse<Object> leaveGroup(
            @AuthUser Long userId,
            @PathVariable Long groupId
    ) {
        groupMemberService.leaveGroup(userId, groupId);
        return ApiResponse.success();
    }

    @GetMapping("/me")
    @Override
    public ApiResponse<GroupV1Dto.ActiveGroupResponse> findMyActiveGroup(@AuthUser Long userId) {
        return ApiResponse.success(
                groupMemberService.findMyActiveGroup(userId)
                        .map(GroupV1Dto.ActiveGroupResponse::from)
                        .orElse(null));
    }
}
