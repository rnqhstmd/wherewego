package com.wherewego.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorType {
    /** 범용 에러 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), "일시적인 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.getReasonPhrase(), "잘못된 요청입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.getReasonPhrase(), "존재하지 않는 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, HttpStatus.CONFLICT.getReasonPhrase(), "이미 존재하는 리소스입니다."),

    /** 인증 */
    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED", "인증이 필요합니다."),
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID", "유효하지 않은 refresh token 입니다."),
    AUTH_KAKAO_API_FAILED(HttpStatus.BAD_GATEWAY, "AUTH_KAKAO_API_FAILED", "카카오 로그인을 일시적으로 사용할 수 없습니다."),
    AUTH_KAKAO_APP_MISMATCH(HttpStatus.UNAUTHORIZED, "AUTH_KAKAO_APP_MISMATCH", "다른 앱에서 발급된 카카오 토큰입니다."),
    AUTH_APPLE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_APPLE_TOKEN_INVALID", "유효하지 않은 Apple 토큰입니다."),
    AUTH_APPLE_JWKS_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AUTH_APPLE_JWKS_UNAVAILABLE", "Apple 로그인을 일시적으로 사용할 수 없습니다."),
    // provider 무관 일시적 서버 과부하/동시성 장애 (Bulkhead 미획득, DB 동시성 retry 소진 등). Kakao/Apple 공용.
    AUTH_LOGIN_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_LOGIN_TEMPORARILY_UNAVAILABLE", "로그인을 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    AUTH_USER_DEACTIVATED(HttpStatus.UNAUTHORIZED, "AUTH_USER_DEACTIVATED", "탈퇴한 사용자입니다."),
    AUTH_USER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),

    /** 봇 연동 */
    BOT_LINK_CODE_INVALID(HttpStatus.BAD_REQUEST, "BOT_LINK_CODE_INVALID", "유효하지 않은 연동코드입니다."),
    BOT_LINK_CODE_EXPIRED(HttpStatus.GONE, "BOT_LINK_CODE_EXPIRED", "연동코드가 만료되었습니다."),
    BOT_LINK_CODE_ALREADY_USED(HttpStatus.CONFLICT, "BOT_LINK_CODE_ALREADY_USED", "이미 사용된 연동코드입니다."),
    BOT_USER_ALREADY_LINKED(HttpStatus.CONFLICT, "BOT_USER_ALREADY_LINKED", "이미 연동된 사용자입니다."),
    BOT_SKILL_SECRET_INVALID(HttpStatus.UNAUTHORIZED, "BOT_SKILL_SECRET_INVALID", "Skill 서명이 유효하지 않습니다."),

    /** 장소 */
    PLC_PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "PLC_PLACE_NOT_FOUND", "장소를 찾을 수 없습니다."),
    PLC_DUPLICATE_PIN(HttpStatus.CONFLICT, "PLC_DUPLICATE_PIN", "이미 저장된 장소입니다."),
    PLC_KAKAO_LOCAL_FAILED(HttpStatus.BAD_GATEWAY, "PLC_KAKAO_LOCAL_FAILED", "장소 검색을 일시적으로 사용할 수 없습니다."),
    PLC_INSTAGRAM_SCRAPE_FAILED(HttpStatus.BAD_GATEWAY, "PLC_INSTAGRAM_SCRAPE_FAILED", "인스타그램 데이터를 가져오지 못했습니다."),
    PLC_GOOGLE_PLACES_FAILED(HttpStatus.BAD_GATEWAY, "PLC_GOOGLE_PLACES_FAILED", "장소 검색을 일시적으로 사용할 수 없습니다."),
    PLACE_SEARCH_KEYWORD_INVALID(HttpStatus.BAD_REQUEST, "PLACE_SEARCH_KEYWORD_INVALID", "검색어를 입력해 주세요."),

    /** 그룹 (Phase 3) */
    GROUP_NAME_INVALID(HttpStatus.BAD_REQUEST, "GROUP_NAME_INVALID", "그룹 이름은 1~30자여야 합니다."),
    GROUP_ALREADY_ACTIVE(HttpStatus.CONFLICT, "GROUP_ALREADY_ACTIVE", "이미 활성 그룹에 속해 있습니다."),
    GROUP_NOT_MEMBER(HttpStatus.FORBIDDEN, "GROUP_NOT_MEMBER", "그룹의 활성 멤버가 아닙니다."),
    GROUP_CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "GROUP_CAPACITY_EXCEEDED", "그룹 정원이 가득 찼습니다."),
    GROUP_ALREADY_MEMBER(HttpStatus.CONFLICT, "GROUP_ALREADY_MEMBER", "이미 이 그룹의 멤버입니다."),
    GROUP_REJOIN_FORBIDDEN(HttpStatus.CONFLICT, "GROUP_REJOIN_FORBIDDEN", "탈퇴한 그룹에는 다시 가입할 수 없습니다."),
    GROUP_OWNER_REQUIRED(HttpStatus.FORBIDDEN, "GROUP_OWNER_REQUIRED", "방장만 삭제할 수 있어요."),
    INVITE_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "INVITE_LINK_NOT_FOUND", "존재하지 않는 초대 링크입니다."),
    INVITE_LINK_EXPIRED(HttpStatus.GONE, "INVITE_LINK_EXPIRED", "초대 링크가 만료되었습니다."),
    INVITE_LINK_SELF_ACCEPT(HttpStatus.BAD_REQUEST, "INVITE_LINK_SELF_ACCEPT", "본인이 발급한 초대 링크는 수락할 수 없습니다."),
    INVITE_LINK_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "INVITE_LINK_RATE_LIMITED", "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."),

    /** 핀 (Phase 4) */
    PIN_NOT_FOUND(HttpStatus.NOT_FOUND, "PIN_NOT_FOUND", "존재하지 않는 핀입니다."),
    PIN_UPDATE_EMPTY(HttpStatus.BAD_REQUEST, "PIN_UPDATE_EMPTY", "수정할 필드가 없습니다."),
    PIN_MEMO_TOO_LONG(HttpStatus.BAD_REQUEST, "PIN_MEMO_TOO_LONG", "메모는 최대 500자까지 입력할 수 있습니다."),
    PIN_MEMO_INVALID(HttpStatus.BAD_REQUEST, "PIN_MEMO_INVALID", "메모 값이 유효하지 않습니다."),
    PIN_TAG_INVALID(HttpStatus.BAD_REQUEST, "PIN_TAG_INVALID", "태그는 REEL, WISH, MEMORY 중 하나여야 합니다."),
    PIN_PLACE_NAME_INVALID(HttpStatus.BAD_REQUEST, "PIN_PLACE_NAME_INVALID", "장소 이름은 1~200자여야 합니다."),
    PIN_ADDRESS_INVALID(HttpStatus.BAD_REQUEST, "PIN_ADDRESS_INVALID", "주소는 최대 500자까지 입력할 수 있습니다."),
    PIN_INSTAGRAM_URL_INVALID(HttpStatus.BAD_REQUEST, "PIN_INSTAGRAM_URL_INVALID", "Instagram URL은 https://로 시작해야 합니다."),
    PIN_COORDINATE_INVALID(HttpStatus.BAD_REQUEST, "PIN_COORDINATE_INVALID", "좌표 범위가 유효하지 않습니다."),
    PIN_PAGE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "PIN_PAGE_SIZE_EXCEEDED", "한 번에 조회할 수 있는 핀은 100개까지입니다."),
    PIN_PAGE_PARAM_INVALID(HttpStatus.BAD_REQUEST, "PIN_PAGE_PARAM_INVALID", "페이지 파라미터가 유효하지 않습니다."),

    /** 방문 체크인·추억 전환 (정책 v2) — 동행 명단에 비활성 멤버가 섞인 경우. */
    PIN_VISIT_COMPANION_INVALID(HttpStatus.BAD_REQUEST, "PIN_VISIT_COMPANION_INVALID", "동행 명단은 이 그룹의 멤버만 포함할 수 있어요."),

    /** 핀 (Phase 12) */
    PIN_CLEANUP_FORBIDDEN(HttpStatus.FORBIDDEN, "PIN_CLEANUP_FORBIDDEN", "정리 권한이 없어요."),
    BOT_REEL_PARSE_FORMAT(HttpStatus.BAD_REQUEST, "BOT_REEL_PARSE_FORMAT", "릴스 장소 선택 형식이 올바르지 않아요."),
    BOT_REEL_PARSE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "BOT_REEL_PARSE_OUT_OF_RANGE", "릴스 장소 번호가 범위를 벗어났어요."),

    /** 핀 사진 (Phase 13) */
    PIN_PHOTO_NOT_MEMORY(HttpStatus.BAD_REQUEST, "PIN_PHOTO_NOT_MEMORY", "사진은 추억(MEMORY) 핀에만 첨부할 수 있어요."),
    PIN_PHOTO_TYPE_INVALID(HttpStatus.BAD_REQUEST, "PIN_PHOTO_TYPE_INVALID", "JPEG, PNG, WebP 이미지만 업로드할 수 있어요."),
    PIN_PHOTO_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "PIN_PHOTO_SIZE_EXCEEDED", "사진은 2MB 이하만 업로드할 수 있어요."),
    PIN_PHOTO_DIMENSION_EXCEEDED(HttpStatus.BAD_REQUEST, "PIN_PHOTO_DIMENSION_EXCEEDED", "사진 해상도가 너무 커요. (장변 4096px 이하)"),
    PIN_PHOTO_FILE_REQUIRED(HttpStatus.BAD_REQUEST, "PIN_PHOTO_FILE_REQUIRED", "업로드할 사진 파일이 없어요."),
    PIN_PHOTO_STORAGE_FAILED(HttpStatus.BAD_GATEWAY, "PIN_PHOTO_STORAGE_FAILED", "사진 저장에 실패했어요. 잠시 후 다시 시도해 주세요."),

    /** 이미지 업로드 공용 (GP-1) — 그룹 대표 이미지 · 프로필 사진. HTTP 의미는 PIN_PHOTO_* 와 동일. */
    IMAGE_FILE_REQUIRED(HttpStatus.BAD_REQUEST, "IMAGE_FILE_REQUIRED", "업로드할 이미지 파일이 없어요."),
    IMAGE_TYPE_INVALID(HttpStatus.BAD_REQUEST, "IMAGE_TYPE_INVALID", "JPEG, PNG, WebP 이미지만 업로드할 수 있어요."),
    IMAGE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "IMAGE_SIZE_EXCEEDED", "이미지는 2MB 이하만 업로드할 수 있어요."),
    IMAGE_STORAGE_FAILED(HttpStatus.BAD_GATEWAY, "IMAGE_STORAGE_FAILED", "이미지 저장에 실패했어요. 잠시 후 다시 시도해 주세요."),

    /** 그룹 채팅 (GC-1) */
    CHAT_KIND_INVALID(HttpStatus.BAD_REQUEST, "CHAT_KIND_INVALID", "지원하지 않는 메시지 종류예요."),
    CHAT_TEXT_INVALID(HttpStatus.BAD_REQUEST, "CHAT_TEXT_INVALID", "메시지는 1~2000자여야 해요."),
    CHAT_REEL_URL_INVALID(HttpStatus.BAD_REQUEST, "CHAT_REEL_URL_INVALID", "인스타그램 릴스 링크(https)만 보낼 수 있어요."),
    CHAT_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_MESSAGE_NOT_FOUND", "존재하지 않는 메시지예요."),
    CHAT_NOT_REEL_LINK(HttpStatus.BAD_REQUEST, "CHAT_NOT_REEL_LINK", "릴스 링크 메시지가 아니에요."),
    CHAT_EXTRACT_FORBIDDEN(HttpStatus.FORBIDDEN, "CHAT_EXTRACT_FORBIDDEN", "발신자만 장소를 등록할 수 있어요."),
    CHAT_PIN_INVALID(HttpStatus.BAD_REQUEST, "CHAT_PIN_INVALID", "이 그룹의 장소만 답장할 수 있어요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
