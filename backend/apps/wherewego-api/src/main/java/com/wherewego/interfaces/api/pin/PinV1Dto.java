package com.wherewego.interfaces.api.pin;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherewego.domain.pin.MemoSource;
import com.wherewego.domain.pin.PinSummary;
import com.wherewego.domain.pin.PinTag;
import com.wherewego.domain.pin.PinUpdateCommand;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

public final class PinV1Dto {

    public record PinSummaryResponse(
            Long id,
            Long groupId,
            Long createdBy,
            String placeName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String instagramUrl,
            String memo,
            MemoSource memoSource,
            PinTag tag,
            ZonedDateTime createdAt
    ) {
        public static PinSummaryResponse from(PinSummary s) {
            return new PinSummaryResponse(
                    s.id(),
                    s.groupId(),
                    s.createdBy(),
                    s.placeName(),
                    s.address(),
                    s.latitude(),
                    s.longitude(),
                    s.instagramUrl(),
                    s.memo(),
                    s.memoSource(),
                    s.tag(),
                    s.createdAt()
            );
        }
    }

    public record PinListResponse(List<PinSummaryResponse> items) {
        public static PinListResponse from(List<PinSummary> list) {
            return new PinListResponse(list.stream().map(PinSummaryResponse::from).toList());
        }
    }

    /**
     * 부분 수정 요청. {@link JsonNode} 로 "키 없음 vs JSON null vs 빈 문자열" 을 구분한다 (Q2).
     */
    public record UpdatePinRequest(JsonNode memo, JsonNode tag) {

        public PinUpdateCommand toCommand() {
            boolean memoProvided = memo != null && !memo.isNull();
            String memoValue = null;
            if (memoProvided) {
                if (!memo.isTextual()) {
                    throw new CoreException(ErrorType.PIN_MEMO_INVALID);
                }
                memoValue = memo.asText();
            }
            boolean tagProvided = tag != null && !tag.isNull();
            PinTag tagValue = null;
            if (tagProvided) {
                if (!tag.isTextual()) {
                    throw new CoreException(ErrorType.PIN_TAG_INVALID);
                }
                try {
                    tagValue = PinTag.valueOf(tag.asText());
                } catch (IllegalArgumentException e) {
                    throw new CoreException(ErrorType.PIN_TAG_INVALID);
                }
            }
            return PinUpdateCommand.of(memoProvided, memoValue, tagProvided, tagValue);
        }
    }

    private PinV1Dto() { }
}
