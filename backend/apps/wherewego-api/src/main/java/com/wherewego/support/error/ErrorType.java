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
    PLC_INSTAGRAM_SCRAPE_FAILED(HttpStatus.BAD_GATEWAY, "PLC_INSTAGRAM_SCRAPE_FAILED", "인스타그램 데이터를 가져오지 못했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
