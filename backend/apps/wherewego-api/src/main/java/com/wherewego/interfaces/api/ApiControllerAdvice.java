package com.wherewego.interfaces.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class ApiControllerAdvice {
    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handle(CoreException e) {
        log.warn("CoreException : {}", e.getCustomMessage() != null ? e.getCustomMessage() : e.getMessage(), e);
        return failureResponse(e.getErrorType(), e.getCustomMessage());
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        String type = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown";
        String value = e.getValue() != null ? e.getValue().toString() : "null";
        String message = String.format("요청 파라미터 '%s' (타입: %s)의 값 '%s'이(가) 잘못되었습니다.", name, type, value);
        return failureResponse(ErrorType.BAD_REQUEST, message);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> String.format("'%s' %s", fe.getField(), fe.getDefaultMessage()))
            .collect(Collectors.joining(", "));
        if (message.isBlank()) {
            message = "요청 본문 검증에 실패했습니다.";
        }
        return failureResponse(ErrorType.BAD_REQUEST, message);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
            .map(v -> String.format("'%s' %s", v.getPropertyPath(), v.getMessage()))
            .collect(Collectors.joining(", "));
        if (message.isBlank()) {
            message = "요청 파라미터 검증에 실패했습니다.";
        }
        return failureResponse(ErrorType.BAD_REQUEST, message);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(MissingServletRequestParameterException e) {
        String name = e.getParameterName();
        String type = e.getParameterType();
        String message = String.format("필수 요청 파라미터 '%s' (타입: %s)가 누락되었습니다.", name, type);
        return failureResponse(ErrorType.BAD_REQUEST, message);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(HttpMessageNotReadableException e) {
        String errorMessage;
        Throwable rootCause = e.getRootCause();

        if (rootCause instanceof InvalidFormatException invalidFormat) {
            String fieldName = invalidFormat.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "?")
                .collect(Collectors.joining("."));

            String valueIndicationMessage = "";
            if (invalidFormat.getTargetType().isEnum()) {
                Class<?> enumClass = invalidFormat.getTargetType();
                String enumValues = Arrays.stream(enumClass.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
                valueIndicationMessage = "사용 가능한 값 : [" + enumValues + "]";
            }

            String expectedType = invalidFormat.getTargetType().getSimpleName();
            Object value = invalidFormat.getValue();

            errorMessage = String.format("필드 '%s'의 값 '%s'이(가) 예상 타입(%s)과 일치하지 않습니다. %s",
                fieldName, value, expectedType, valueIndicationMessage);

        } else if (rootCause instanceof MismatchedInputException mismatchedInput) {
            String fieldPath = mismatchedInput.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "?")
                .collect(Collectors.joining("."));
            errorMessage = String.format("필수 필드 '%s'이(가) 누락되었습니다.", fieldPath);

        } else if (rootCause instanceof JsonMappingException jsonMapping) {
            String fieldPath = jsonMapping.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "?")
                .collect(Collectors.joining("."));
            errorMessage = String.format("필드 '%s'에서 JSON 매핑 오류가 발생했습니다: %s",
                fieldPath, jsonMapping.getOriginalMessage());

        } else {
            errorMessage = "요청 본문을 처리하는 중 오류가 발생했습니다. JSON 메세지 규격을 확인해주세요.";
        }

        return failureResponse(ErrorType.BAD_REQUEST, errorMessage);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleBadRequest(ServerWebInputException e) {
        String missingParams = extractMissingParameter(e.getReason() != null ? e.getReason() : "");
        if (!missingParams.isEmpty()) {
            String message = String.format("필수 요청 값 '%s'가 누락되었습니다.", missingParams);
            return failureResponse(ErrorType.BAD_REQUEST, message);
        } else {
            return failureResponse(ErrorType.BAD_REQUEST, null);
        }
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handleNotFound(NoResourceFoundException e) {
        return failureResponse(ErrorType.NOT_FOUND, null);
    }

    /**
     * Phase 13: 멀티파트 크기 초과 (spring.servlet.multipart.max-file-size 초과).
     * 컨트롤러의 2MB 검증보다 먼저 Spring 이 던질 수 있으므로 동일 에러코드로 매핑한다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<?>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return failureResponse(ErrorType.PIN_PHOTO_SIZE_EXCEEDED, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("DataIntegrityViolationException : {}", e.getMessage());
        String message = e.getMessage() != null ? e.getMessage() : "";
        String cause = e.getMostSpecificCause() != null && e.getMostSpecificCause().getMessage() != null
            ? e.getMostSpecificCause().getMessage()
            : "";
        String combined = message + " " + cause;
        if (combined.contains("uq_pins_group_instagram") || combined.contains("uq_pin_group_instagram")) {
            return failureResponse(ErrorType.PLC_DUPLICATE_PIN, null);
        }
        // Phase 3 - Group 도메인 DB 제약 위반 매핑
        // GM-1 이후 dead path(uq_group_members_active_user DROP). 웹 중단 예정이라 유지.
        if (combined.contains("uq_group_members_active_user")) {
            return failureResponse(ErrorType.GROUP_ALREADY_ACTIVE, null);
        }
        if (combined.contains("uq_group_members_pair")) {
            return failureResponse(ErrorType.GROUP_REJOIN_FORBIDDEN, null);
        }
        if (combined.contains("uq_invite_links_token")) {
            return failureResponse(ErrorType.INTERNAL_ERROR, null);
        }
        return failureResponse(ErrorType.INTERNAL_ERROR, null);
    }

    @ExceptionHandler
    public ResponseEntity<ApiResponse<?>> handle(Throwable e) {
        log.error("Exception : {}", e.getMessage(), e);
        return failureResponse(ErrorType.INTERNAL_ERROR, null);
    }

    private String extractMissingParameter(String message) {
        Pattern pattern = Pattern.compile("'(.+?)'");
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private ResponseEntity<ApiResponse<?>> failureResponse(ErrorType errorType, String errorMessage) {
        return ResponseEntity.status(errorType.getStatus())
            .body(ApiResponse.fail(errorType.getCode(), errorMessage != null ? errorMessage : errorType.getMessage()));
    }
}
