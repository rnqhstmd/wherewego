package com.wherewego.interfaces.api.group;

import com.wherewego.domain.group.ActiveGroupInfo;
import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.InviteAcceptResult;
import com.wherewego.domain.group.InviteLinkIssueResult;

import java.time.Instant;
import java.time.ZonedDateTime;

public class GroupV1Dto {

    /**
     * 그룹 생성 요청. 이름 검증은 서비스 레이어에서 수행하여
     * Bean Validation 대신 도메인 에러 코드(GROUP_NAME_INVALID)로 통일한다.
     */
    public record CreateGroupRequest(String name) { }

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

    public record ActiveGroupResponse(
            Long groupId,
            String name,
            ZonedDateTime createdAt,
            long memberCount
    ) {
        public static ActiveGroupResponse from(ActiveGroupInfo info) {
            return new ActiveGroupResponse(
                    info.groupId(),
                    info.name(),
                    info.createdAt(),
                    info.memberCount()
            );
        }
    }

    private GroupV1Dto() { }
}
