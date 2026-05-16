package com.wherewego.interfaces.api.group;

import com.wherewego.domain.group.ActiveGroupInfo;
import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.InviteAcceptResult;
import com.wherewego.domain.group.InviteLinkIssueResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.ZonedDateTime;

public class GroupV1Dto {

    public record CreateGroupRequest(
            @NotBlank
            @Size(max = 30)
            String name
    ) { }

    public record GroupCreatedResponse(Long groupId, String name, ZonedDateTime createdAt) {
        public static GroupCreatedResponse from(GroupCreatedResult result) {
            return new GroupCreatedResponse(result.groupId(), result.name(), result.createdAt());
        }
    }

    public record InviteLinkResponse(String token, Instant expiresAt) {
        public static InviteLinkResponse from(InviteLinkIssueResult result) {
            return new InviteLinkResponse(result.token(), result.expiresAt());
        }
    }

    public record InviteAcceptResponse(Long groupId, Instant acceptedAt) {
        public static InviteAcceptResponse from(InviteAcceptResult result) {
            return new InviteAcceptResponse(result.groupId(), result.acceptedAt());
        }
    }

    public record ActiveGroupResponse(Long groupId, String name, ZonedDateTime createdAt) {
        public static ActiveGroupResponse from(ActiveGroupInfo info) {
            return new ActiveGroupResponse(info.groupId(), info.name(), info.createdAt());
        }
    }

    private GroupV1Dto() { }
}
