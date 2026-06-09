package com.wherewego.interfaces.api.group;

import com.wherewego.domain.group.ActiveGroupInfo;
import com.wherewego.domain.group.GroupCreatedResult;
import com.wherewego.domain.group.GroupMemberService.GroupMemberResult;
import com.wherewego.domain.group.GroupSummary;
import com.wherewego.domain.group.InviteAcceptResult;
import com.wherewego.domain.group.InviteLinkIssueResult;
import com.wherewego.domain.group.InviteLinkPreviewResult;

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

    /**
     * 초대 링크 발급 응답.
     * - token: 기존 UUID 토큰 (accept API 호출 키, BC).
     * - slug: base56 8자 단축 슬러그.
     * - shareUrl: `${app.invite.share-base-url}/invite/{slug}` 단축 공유 URL.
     */
    public record InviteLinkResponse(String token, String slug, Instant expiresAt, String shareUrl) {
        public static InviteLinkResponse from(InviteLinkIssueResult result, String shareBaseUrl) {
            String shareUrl = buildShareUrl(shareBaseUrl, result.slug());
            return new InviteLinkResponse(result.token(), result.slug(), result.expiresAt(), shareUrl);
        }

        private static String buildShareUrl(String baseUrl, String slug) {
            if (baseUrl == null || baseUrl.isBlank()) {
                return "/invite/" + slug;
            }
            String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return trimmed + "/invite/" + slug;
        }
    }

    /**
     * 초대 링크 공개 미리보기 응답.
     * 토큰은 로그인 후 기존 accept API 호출에 사용된다.
     */
    public record InviteLinkPreviewResponse(
            String token,
            String groupName,
            String inviterNickname,
            Instant expiresAt
    ) {
        public static InviteLinkPreviewResponse from(InviteLinkPreviewResult result) {
            return new InviteLinkPreviewResponse(
                    result.token(),
                    result.groupName(),
                    result.inviterNickname(),
                    result.expiresAt()
            );
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

    public record GroupSummaryResponse(
            Long groupId,
            String name,
            ZonedDateTime createdAt,
            long memberCount
    ) {
        public static GroupSummaryResponse from(GroupSummary summary) {
            return new GroupSummaryResponse(
                    summary.groupId(),
                    summary.name(),
                    summary.createdAt(),
                    summary.memberCount()
            );
        }
    }

    /**
     * 그룹원 목록 항목 (GM-2 그룹관리). {@code isOwner} 는 방장(joined_at 최소) 여부.
     */
    public record MemberResponse(
            Long userId,
            String nickname,
            Instant joinedAt,
            boolean isOwner
    ) {
        public static MemberResponse from(GroupMemberResult result) {
            return new MemberResponse(
                    result.userId(),
                    result.nickname(),
                    result.joinedAt(),
                    result.isOwner()
            );
        }
    }

    /**
     * 그룹명 수정 요청 (GM-2). 이름 검증은 서비스 레이어에서 GROUP_NAME_INVALID 로 통일한다.
     */
    public record UpdateGroupNameRequest(String name) { }

    private GroupV1Dto() { }
}
