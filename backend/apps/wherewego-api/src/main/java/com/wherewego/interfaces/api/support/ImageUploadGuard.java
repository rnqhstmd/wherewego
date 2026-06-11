package com.wherewego.interfaces.api.support;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * 이미지 업로드 멀티파트 3중 검증 유틸 (GP-1). 핀 사진 컨트롤러의 검증 로직
 * (contentType 화이트리스트 → 2MB → 매직바이트)을 추출·일반화했다.
 *
 * <p>핀 사진은 클라 호환을 위해 기존 {@code PIN_PHOTO_*} 에러타입을 그대로 던져야 하고,
 * 신규 그룹/프사 업로드는 범용 {@code IMAGE_*} 를 던진다. 따라서 던질 에러타입 3종(파일 없음/타입/크기)을
 * 파라미터로 받는 코어 메서드를 두고, 신규 호출자용 기본 오버로드({@code IMAGE_*})를 별도 제공한다.</p>
 *
 * <p>매직바이트 게이트는 Content-Type 헤더 위조를 보완한다 — 실제 바이트 시그니처가
 * JPEG/PNG/WebP 가 아니면 타입 에러로 거부한다.</p>
 */
public final class ImageUploadGuard {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadGuard.class);

    /** 허용 contentType (핀 사진과 동일). */
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    /** 최대 크기 2MB (핀 사진과 동일). */
    private static final long MAX_IMAGE_SIZE = 2L * 1024 * 1024;

    private ImageUploadGuard() {
    }

    /**
     * 신규(그룹 대표 이미지 · 프로필 사진) 업로드용 검증. 범용 {@code IMAGE_*} 에러타입을 던진다.
     *
     * @param file 멀티파트 파일
     * @return 검증 통과한 원본 bytes
     */
    public static byte[] readValidatedImage(MultipartFile file) {
        return readValidatedImage(
                file,
                ErrorType.IMAGE_FILE_REQUIRED,
                ErrorType.IMAGE_TYPE_INVALID,
                ErrorType.IMAGE_SIZE_EXCEEDED,
                ErrorType.IMAGE_STORAGE_FAILED);
    }

    /**
     * 던질 에러타입 4종을 호출자가 지정하는 코어 검증. 핀 사진은 기존 {@code PIN_PHOTO_*} 를 그대로 전달해
     * 클라이언트 응답 코드 계약을 보존한다(동작 무변경 위임).
     *
     * <p>빈 파일 → fileRequired, contentType 화이트리스트 미포함 또는 매직바이트 불일치 → typeInvalid,
     * 2MB 초과 → sizeExceeded, 멀티파트 바이트 판독 실패(IOException) → storageFailed.</p>
     *
     * @param file          멀티파트 파일
     * @param fileRequired  빈/누락 파일에 던질 에러타입
     * @param typeInvalid   타입/매직바이트 불일치에 던질 에러타입
     * @param sizeExceeded  크기 초과에 던질 에러타입
     * @param storageFailed 멀티파트 바이트 판독 실패에 던질 에러타입
     * @return 검증 통과한 원본 bytes
     */
    public static byte[] readValidatedImage(MultipartFile file,
                                            ErrorType fileRequired,
                                            ErrorType typeInvalid,
                                            ErrorType sizeExceeded,
                                            ErrorType storageFailed) {
        if (file == null || file.isEmpty()) {
            throw new CoreException(fileRequired);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new CoreException(typeInvalid);
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new CoreException(sizeExceeded);
        }
        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (IOException e) {
            log.warn("multipart image read failed", e);
            throw new CoreException(storageFailed);
        }
        // 2차 게이트: Content-Type 헤더는 위조 가능하므로 실제 매직바이트로 이미지 여부 검증.
        if (!isAllowedImageMagic(imageBytes)) {
            throw new CoreException(typeInvalid);
        }
        return imageBytes;
    }

    /**
     * 업로드 파일의 시작 매직바이트가 실제 허용 이미지(JPEG/PNG/WebP)인지 검증한다 (Content-Type 헤더 보완).
     * 길이가 부족한(짧은) 파일은 거부한다.
     */
    public static boolean isAllowedImageMagic(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        // JPEG: FF D8 FF
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E
                && (bytes[3] & 0xFF) == 0x47
                && (bytes[4] & 0xFF) == 0x0D
                && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A
                && (bytes[7] & 0xFF) == 0x0A) {
            return true;
        }
        // WebP: 바이트 0~3 = "RIFF", 바이트 8~11 = "WEBP"
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return true;
        }
        return false;
    }
}
