package com.wherewego.domain.chatbot;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

/**
 * 챗봇 응답으로 노출되는 사용자 친화 메시지 화이트리스트.
 *
 * <p>{@link CoreException#getMessage()}는 내부 오류 컨텍스트(customMessage, HTTP 상태 등)를
 * 포함할 수 있으므로 챗봇 응답에 그대로 노출하지 않는다. {@link ErrorType} 기반으로 매핑된
 * 안전한 문구만 사용자에게 전달한다.</p>
 */
public final class ChatbotErrorMessages {

    private static final String DEFAULT_MESSAGE = "일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요.";

    private ChatbotErrorMessages() { }

    public static String userMessage(CoreException e) {
        return switch (e.getErrorType()) {
            case BOT_LINK_CODE_INVALID -> "유효하지 않은 연동코드입니다.";
            case BOT_LINK_CODE_EXPIRED -> "연동코드가 만료되었습니다. 앱에서 다시 발급해 주세요.";
            case BOT_LINK_CODE_ALREADY_USED -> "이미 사용된 연동코드입니다.";
            case BOT_USER_ALREADY_LINKED -> "이미 연동된 계정입니다.";
            case PLC_PLACE_NOT_FOUND -> "장소를 찾지 못했어요. 직접 검색해 주세요.";
            case PLC_DUPLICATE_PIN -> "이미 저장된 장소예요.";
            case PLC_KAKAO_LOCAL_FAILED -> "장소 검색을 일시적으로 사용할 수 없어요. 잠시 후 다시 시도해 주세요.";
            case PLC_GOOGLE_PLACES_FAILED -> "장소 검색을 일시적으로 사용할 수 없어요. 잠시 후 다시 시도해 주세요.";
            case PLC_INSTAGRAM_SCRAPE_FAILED -> "처리가 지연되었어요. 다시 시도해 주세요.";
            default -> DEFAULT_MESSAGE;
        };
    }
}
