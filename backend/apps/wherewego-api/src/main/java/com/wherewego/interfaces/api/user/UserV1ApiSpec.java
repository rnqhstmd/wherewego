package com.wherewego.interfaces.api.user;

import com.wherewego.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "User V1 API", description = "사용자 프로필 조회/수정 API 입니다.")
public interface UserV1ApiSpec {

    @Operation(
            summary = "내 정보 조회",
            description = "현재 로그인 사용자의 프로필 정보를 반환합니다."
    )
    ApiResponse<UserV1Dto.UserResponse> getCurrentUser(
            @Parameter(hidden = true) Long userId
    );

    @Operation(
            summary = "닉네임 변경",
            description = "현재 로그인 사용자의 닉네임을 변경합니다. 한글/영문/숫자 2~12자만 허용됩니다."
    )
    ApiResponse<UserV1Dto.UserResponse> updateNickname(
            @Parameter(hidden = true) Long userId,
            UserV1Dto.UpdateNicknameRequest request
    );

    @Operation(
            summary = "회원 탈퇴",
            description = "현재 로그인 사용자의 계정을 삭제(soft delete)합니다. 그룹 탈퇴/봇 매핑 해제/채팅 정리/" +
                    "디바이스 정리/refresh 무효화 후 사용자를 비활성화하며, Apple 계정은 커밋 후 best-effort 로 revoke 합니다."
    )
    ApiResponse<Object> deleteMe(
            @Parameter(hidden = true) Long userId
    );

    @Operation(
            summary = "내 프로필 사진 업로드/교체",
            description = "본인이 멀티파트 file 로 프로필 사진을 올립니다 (GP-1 FR-3). JPEG/PNG/WebP·2MB 이하만 허용하며" +
                    "(IMAGE_TYPE_INVALID/IMAGE_SIZE_EXCEEDED/IMAGE_FILE_REQUIRED), 교체 시 이전 객체는 best-effort 회수됩니다. " +
                    "응답의 profileImageUrl 은 유효 프사 URL(업로드 썸네일 우선 → 카카오 URL 폴백 → null)입니다."
    )
    ApiResponse<UserV1Dto.UserResponse> uploadProfileImage(
            @Parameter(hidden = true) Long userId,
            MultipartFile file
    );

    @Operation(
            summary = "내 프로필 사진 제거",
            description = "본인이 프로필 사진을 제거합니다 (GP-1 FR-3/Q4). 업로드 키와 카카오 URL 까지 모두 비워 " +
                    "'프사 없음' 상태로 확정하고(동기화 중단으로 자동 복원 없음), S3 객체를 best-effort 삭제합니다. " +
                    "응답의 profileImageUrl 은 null(클라는 이니셜 원형으로 복귀)."
    )
    ApiResponse<UserV1Dto.UserResponse> deleteProfileImage(
            @Parameter(hidden = true) Long userId
    );
}
