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

    /** 그룹 (Phase 3) */
    GROUP_NAME_INVALID(HttpStatus.BAD_REQUEST, "GROUP_NAME_INVALID", "그룹 이름은 1~30자여야 합니다."),
    GROUP_ALREADY_ACTIVE(HttpStatus.CONFLICT, "GROUP_ALREADY_ACTIVE", "이미 활성 그룹에 속해 있습니다."),
    GROUP_NOT_MEMBER(HttpStatus.FORBIDDEN, "GROUP_NOT_MEMBER", "그룹의 활성 멤버가 아닙니다."),
    GROUP_CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "GROUP_CAPACITY_EXCEEDED", "그룹 정원이 가득 찼습니다."),
    GROUP_REJOIN_FORBIDDEN(HttpStatus.CONFLICT, "GROUP_REJOIN_FORBIDDEN", "탈퇴한 그룹에는 다시 가입할 수 없습니다."),
    INVITE_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "INVITE_LINK_NOT_FOUND", "존재하지 않는 초대 링크입니다."),
    INVITE_LINK_EXPIRED(HttpStatus.GONE, "INVITE_LINK_EXPIRED", "초대 링크가 만료되었습니다."),
    INVITE_LINK_ALREADY_USED(HttpStatus.CONFLICT, "INVITE_LINK_ALREADY_USED", "이미 사용된 초대 링크입니다."),
    INVITE_LINK_SELF_ACCEPT(HttpStatus.BAD_REQUEST, "INVITE_LINK_SELF_ACCEPT", "본인이 발급한 초대 링크는 수락할 수 없습니다."),

    /** 핀 (Phase 4) */
    PIN_NOT_FOUND(HttpStatus.NOT_FOUND, "PIN_NOT_FOUND", "존재하지 않는 핀입니다."),
    PIN_UPDATE_EMPTY(HttpStatus.BAD_REQUEST, "PIN_UPDATE_EMPTY", "수정할 필드(memo 또는 tag)가 없습니다."),
    PIN_MEMO_TOO_LONG(HttpStatus.BAD_REQUEST, "PIN_MEMO_TOO_LONG", "메모는 최대 500자까지 입력할 수 있습니다."),
    PIN_MEMO_INVALID(HttpStatus.BAD_REQUEST, "PIN_MEMO_INVALID", "메모 값이 유효하지 않습니다."),
    PIN_TAG_INVALID(HttpStatus.BAD_REQUEST, "PIN_TAG_INVALID", "태그는 PLACE 또는 MEMORY 중 하나여야 합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
