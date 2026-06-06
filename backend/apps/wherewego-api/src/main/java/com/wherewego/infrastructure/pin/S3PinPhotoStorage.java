package com.wherewego.infrastructure.pin;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
import com.sksamuel.scrimage.nio.JpegWriter;
import com.wherewego.config.env.S3Properties;
import com.wherewego.domain.pin.PinPhotoStorage;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.UUID;

/**
 * Phase 13: {@link PinPhotoStorage} 의 AWS S3 어댑터 (헥사고날 인프라 레이어).
 *
 * <p>검증된 원본 bytes → 픽셀 상한 검증(장변 4096px) → 썸네일 WebP 인코딩(장변 256px) →
 * 원본·썸네일 2객체 put 까지 담당한다. 키 스킴은 {@code pins/{groupId}/{pinId}/{uuid}.jpg} /
 * {@code ..._thumb.webp}(uuid 공유, BR-7), 캐시 헤더는 {@code public, max-age=31536000, immutable}(AC-6).</p>
 *
 * <p>원자성(BR-5/AC-8): 원본 put 성공 후 썸네일 인코딩/put 실패 시 원본 deleteObject 후 예외 재throw.
 * S3 {@link SdkException} 류는 {@link ErrorType#PIN_PHOTO_STORAGE_FAILED} 로 래핑한다(Q4).</p>
 */
@Component
@RequiredArgsConstructor
public class S3PinPhotoStorage implements PinPhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(S3PinPhotoStorage.class);

    /** 디코딩 직후 장변 픽셀 상한 (Q2). 초과 시 PIN_PHOTO_DIMENSION_EXCEEDED. */
    private static final int MAX_DIMENSION = 4096;

    /** 썸네일 장변 픽셀 (FR-PIN-9c). */
    private static final int THUMBNAIL_MAX = 256;

    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    /** JPEG 폴백 썸네일 품질(0~100). WebP 인코딩 실패 시에만 사용. */
    private static final int JPEG_FALLBACK_QUALITY = 82;

    private final S3Client s3Client;
    private final S3Properties props;

    @Override
    public StoredPhoto store(Long groupId, Long pinId, byte[] imageBytes, String contentType) {
        // ① 픽셀 상한 선확인 (decompression bomb 방지: 헤더 차원 → full 디코딩) + 디코딩 공유
        ImmutableImage image = decodeWithDimensionGuard(imageBytes);

        // ② UUID 키 생성 (원본/썸네일 동일 uuid 공유). 썸네일 키 확장자는 인코딩 포맷(webp/jpg) 확정 후 결정.
        String uuid = UUID.randomUUID().toString();
        String photoKey = String.format("pins/%d/%d/%s.jpg", groupId, pinId, uuid);

        // ④ 원본 put (실패 시 SdkException → STORAGE_FAILED)
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(photoKey)
                            .contentType(contentType)
                            .cacheControl(CACHE_CONTROL)
                            .build(),
                    RequestBody.fromBytes(imageBytes));
        } catch (SdkException e) {
            log.warn("S3 원본 putObject 실패 (key={})", photoKey, e);
            throw new CoreException(ErrorType.PIN_PHOTO_STORAGE_FAILED);
        }

        // ⑤ 썸네일 인코딩: WebP 우선, 실패 시 JPEG 폴백.
        //    로컬 macOS 등에서 scrimage 번들 cwebp 네이티브 실행이 격리/권한으로 막히면 WebP 인코딩이 실패하는데,
        //    그때 업로드 전체를 실패시키지 않고 순수 자바 JPEG 인코딩으로 대체한다(운영 Linux 에선 WebP 정상).
        EncodedThumbnail thumb;
        try {
            thumb = encodeThumbnailWithFallback(image);
        } catch (RuntimeException e) {
            // WebP·JPEG 모두 실패(디코딩 불가 등) — 이미 올라간 원본 정리 후 재throw.
            deleteObjectQuietly(photoKey);
            throw e;
        }
        String thumbnailKey = String.format("pins/%d/%d/%s_thumb.%s", groupId, pinId, uuid, thumb.extension());

        // ⑥ 원자성(BR-5/AC-8): 원본 성공 후 썸네일 put 실패 시 원본 정리 후 재throw.
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(thumbnailKey)
                            .contentType(thumb.contentType())
                            .cacheControl(CACHE_CONTROL)
                            .build(),
                    RequestBody.fromBytes(thumb.bytes()));
        } catch (SdkException e) {
            log.warn("S3 썸네일 putObject 실패 (key={}), 원본 정리", thumbnailKey, e);
            deleteObjectQuietly(photoKey);
            throw new CoreException(ErrorType.PIN_PHOTO_STORAGE_FAILED);
        }

        return new StoredPhoto(photoKey, thumbnailKey);
    }

    @Override
    public void deleteQuietly(String photoKey, String thumbnailKey) {
        deleteObjectQuietly(photoKey);
        deleteObjectQuietly(thumbnailKey);
    }

    /**
     * 헤더 차원 선확인으로 decompression bomb 을 차단한 뒤 full 디코딩한다.
     * 장변 4096px 초과 시 {@link ErrorType#PIN_PHOTO_DIMENSION_EXCEEDED}.
     */
    private ImmutableImage decodeWithDimensionGuard(byte[] imageBytes) {
        // 헤더만 읽어 차원 선확인 (full 디코딩 전 OOM 방지)
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (iis != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, true, true);
                        int width = reader.getWidth(0);
                        int height = reader.getHeight(0);
                        if (Math.max(width, height) > MAX_DIMENSION) {
                            throw new CoreException(ErrorType.PIN_PHOTO_DIMENSION_EXCEEDED);
                        }
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (IOException e) {
            // 헤더 판독 실패는 디코딩 단계에서 다시 시도하도록 통과시킨다.
            log.warn("photo header probe failed, falling back to full decode", e);
        }

        ImmutableImage image;
        try {
            image = ImmutableImage.loader().fromBytes(imageBytes);
        } catch (IOException e) {
            // 디코딩 불가능한 손상/비이미지 — 컨트롤러 타입 검증을 통과했으나 실제 디코딩 실패.
            throw new CoreException(ErrorType.PIN_PHOTO_TYPE_INVALID);
        }
        // 헤더 판독을 건너뛴 포맷(예: WebP)을 위해 디코딩 후 한 번 더 확인.
        if (Math.max(image.width, image.height) > MAX_DIMENSION) {
            throw new CoreException(ErrorType.PIN_PHOTO_DIMENSION_EXCEEDED);
        }
        return image;
    }

    /** 인코딩된 썸네일(바이트 + content-type + 키 확장자). WebP 우선, 실패 시 JPEG. */
    private record EncodedThumbnail(byte[] bytes, String contentType, String extension) {}

    /**
     * 장변 {@value #THUMBNAIL_MAX}px 박스로 축소 후 인코딩한다. WebP(번들 cwebp 네이티브) 우선,
     * 네이티브 실행 실패(로컬 macOS 격리/권한 등) 시 순수 자바 JPEG 로 폴백한다.
     * 둘 다 실패하면(이미지 디코딩 불가 등) IllegalStateException — store() 가 원본을 정리한다.
     */
    private EncodedThumbnail encodeThumbnailWithFallback(ImmutableImage image) {
        ImmutableImage scaled = image.max(THUMBNAIL_MAX, THUMBNAIL_MAX);
        try {
            return new EncodedThumbnail(scaled.bytes(WebpWriter.DEFAULT), "image/webp", "webp");
        } catch (Exception webpError) {
            // WebP 네이티브(cwebp) 실행/인코딩 실패 — 순수 자바 JPEG 로 폴백.
            log.warn("WebP 썸네일 인코딩 실패 — JPEG 폴백 (로컬 네이티브 cwebp 실행 불가 가능)", webpError);
            try {
                return new EncodedThumbnail(
                        scaled.bytes(JpegWriter.compression(JPEG_FALLBACK_QUALITY)),
                        "image/jpeg", "jpg");
            } catch (IOException jpegError) {
                throw new IllegalStateException("thumbnail encoding failed (webp + jpeg)", jpegError);
            }
        }
    }

    /** 단일 객체 best-effort 삭제. null 키는 skip, 실패는 로그만(예외 전파 금지). */
    private void deleteObjectQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .build());
        } catch (SdkException e) {
            log.warn("S3 deleteObject failed (best-effort, orphan harmless): key={}", key, e);
        }
    }
}
