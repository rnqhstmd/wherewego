package com.wherewego.domain.chatbot;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 12: 릴스 다중 선택(MULTI_SELECTING) 단계의 콤마 파서.
 *
 * <p>입력 예시: "1,3,5", "1, 2, ,3", "전부", "건너뛰기" (후자 둘은 핸들러가 별도 처리).
 * 본 파서는 콤마 + 숫자 토큰화에만 집중한다.</p>
 *
 * <p>중복은 침묵 dedup (D-2). 한 토큰이라도 실패하면 전체 거부.</p>
 */
@Component
public class ReelCommaParser {

    /** ASCII 0-9 한정. 전각 숫자/zero-width/NBSP/한글/영문 모두 차단. */
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    public enum Status {
        OK,
        FORMAT_MISMATCH,
        OUT_OF_RANGE,
        EMPTY
    }

    /**
     * 파싱 결과.
     *
     * @param status  파싱 상태
     * @param indices {@link Status#OK} 일 때만 의미 있음 (입력 순서 보존, dedup 완료). 그 외 빈 리스트.
     */
    public record Result(Status status, List<Integer> indices) {
        public static Result ok(List<Integer> indices) {
            return new Result(Status.OK, List.copyOf(indices));
        }

        public static Result formatMismatch() {
            return new Result(Status.FORMAT_MISMATCH, List.of());
        }

        public static Result outOfRange() {
            return new Result(Status.OUT_OF_RANGE, List.of());
        }

        public static Result empty() {
            return new Result(Status.EMPTY, List.of());
        }
    }

    /**
     * 콤마 파싱 (D-2 침묵 dedup).
     *
     * 입력 정규화 + 단계:
     *  1) input.split(",", -1)
     *  2) 각 토큰 trim 후 빈 토큰 무시 (trailing/연속 콤마 허용)
     *  3) 토큰 매칭: ^\d+$ (ASCII 0-9 한정).
     *     ※ 전각 숫자 '１,３,５', zero-width space 'U+200B', NBSP 'U+00A0',
     *        한글/영문 등은 모두 본 정규식에서 차단 → FORMAT_MISMATCH.
     *  4) Integer.parseInt 시도:
     *      - NumberFormatException (Long 범위 초과 또는 매우 큰 수)
     *        → FORMAT_MISMATCH 로 매핑 (사용자에게 동일한 안내 문자열로 노출).
     *  5) 변환된 정수가 1..totalCount 범위 위반 → OUT_OF_RANGE
     *  6) LinkedHashSet 으로 dedup (입력 순서 보존)
     *  7) 비어 있으면 EMPTY (',', ' ,' 등 모든 토큰이 빈 경우)
     */
    public Result parse(String input, int totalCount) {
        if (input == null) {
            return Result.empty();
        }
        String[] rawTokens = input.split(",", -1);

        LinkedHashSet<Integer> collected = new LinkedHashSet<>();
        boolean sawAnyToken = false;

        for (String raw : rawTokens) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            sawAnyToken = true;

            if (!DIGITS.matcher(token).matches()) {
                return Result.formatMismatch();
            }
            int value;
            try {
                value = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                // Long 범위 초과 또는 int 범위 초과 시 FORMAT_MISMATCH 로 매핑.
                return Result.formatMismatch();
            }
            if (value < 1 || value > totalCount) {
                return Result.outOfRange();
            }
            collected.add(value);
        }

        if (!sawAnyToken || collected.isEmpty()) {
            return Result.empty();
        }
        return Result.ok(new ArrayList<>(collected));
    }
}
