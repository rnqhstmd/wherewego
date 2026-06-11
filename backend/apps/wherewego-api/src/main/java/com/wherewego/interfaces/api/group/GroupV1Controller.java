package com.wherewego.interfaces.api.group;

import com.wherewego.config.env.InviteProperties;
import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.group.GroupMemberService;
import com.wherewego.interfaces.api.ApiResponse;
import com.wherewego.interfaces.api.support.ImageUploadGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class GroupV1Controller implements GroupV1ApiSpec {

    private final GroupMemberService groupMemberService;
    private final InviteProperties inviteProperties;

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
                        groupMemberService.issueInviteLink(userId, groupId),
                        inviteProperties.shareBaseUrl()));
    }

    @GetMapping("/{groupId}/invite-links/current")
    @Override
    public ApiResponse<GroupV1Dto.InviteLinkResponse> currentInviteLink(
            @AuthUser Long userId,
            @PathVariable Long groupId
    ) {
        return ApiResponse.success(
                groupMemberService.currentInviteLink(userId, groupId)
                        .map(result -> GroupV1Dto.InviteLinkResponse.from(result, inviteProperties.shareBaseUrl()))
                        .orElse(null));
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

    @GetMapping("/invite-links/by-slug/{slug}")
    @Override
    public ApiResponse<GroupV1Dto.InviteLinkPreviewResponse> previewInviteLinkBySlug(
            @PathVariable String slug
    ) {
        return ApiResponse.success(
                GroupV1Dto.InviteLinkPreviewResponse.from(
                        groupMemberService.previewBySlug(slug)));
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

    @GetMapping
    @Override
    public ApiResponse<List<GroupV1Dto.GroupSummaryResponse>> listMyGroups(@AuthUser Long userId) {
        return ApiResponse.success(
                groupMemberService.listMyGroupsWithMembers(userId).stream()
                        .map(GroupV1Dto.GroupSummaryResponse::from)
                        .toList());
    }

    @GetMapping("/{groupId}/members")
    @Override
    public ApiResponse<List<GroupV1Dto.MemberResponse>> listMembers(
            @AuthUser Long userId,
            @PathVariable Long groupId
    ) {
        return ApiResponse.success(
                groupMemberService.listMembers(userId, groupId).stream()
                        .map(GroupV1Dto.MemberResponse::from)
                        .toList());
    }

    @PatchMapping("/{groupId}")
    @Override
    public ApiResponse<Object> renameGroup(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @RequestBody GroupV1Dto.UpdateGroupNameRequest request
    ) {
        groupMemberService.renameGroup(userId, groupId, request.name());
        return ApiResponse.success();
    }

    @DeleteMapping("/{groupId}")
    @Override
    public ApiResponse<Object> deleteGroup(
            @AuthUser Long userId,
            @PathVariable Long groupId
    ) {
        groupMemberService.deleteGroup(userId, groupId);
        return ApiResponse.success();
    }

    @PostMapping(value = "/{groupId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Override
    public ApiResponse<GroupV1Dto.GroupImageResponse> uploadGroupImage(
            @AuthUser Long userId,
            @PathVariable Long groupId,
            @RequestParam("file") MultipartFile file
    ) {
        // GP-1: 3중 검증(타입/크기/매직바이트)을 ImageUploadGuard 로 위임. 범용 IMAGE_* 에러타입.
        byte[] imageBytes = ImageUploadGuard.readValidatedImage(file);
        return ApiResponse.success(GroupV1Dto.GroupImageResponse.from(
                groupMemberService.updateGroupImage(userId, groupId, imageBytes, file.getContentType())));
    }

    @DeleteMapping("/{groupId}/image")
    @Override
    public ApiResponse<GroupV1Dto.GroupImageResponse> deleteGroupImage(
            @AuthUser Long userId,
            @PathVariable Long groupId
    ) {
        return ApiResponse.success(GroupV1Dto.GroupImageResponse.from(
                groupMemberService.clearGroupImage(userId, groupId)));
    }
}
