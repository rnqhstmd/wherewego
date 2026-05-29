package com.wherewego.domain.pin;

/**
 * 추억핀 사진 저장 포트 (Phase 13). 헥사고날 아키텍처의 도메인 포트이며,
 * 구현체(어댑터)는 {@code infrastructure.pin} 의 S3 기반 {@code S3PinPhotoStorage} 이다.
 *
 * <p>검증된 원본 bytes 를 입력으로 받아 픽셀 상한 검증 → 썸네일 생성 → 원본·썸네일 2객체 저장까지
 * 담당한다. 키 스킴/캐시 헤더/원자성 등 스토리지 세부는 어댑터 책임이며, 서비스는 본 포트만 의존한다.</p>
 */
public interface PinPhotoStorage {

    /**
     * 검증된 원본 bytes → 픽셀 상한 검증 → 썸네일 생성 → 원본·썸네일 put.
     * <p>부분 실패(원본 성공 후 썸네일 실패 등) 시 이미 업로드한 객체를 정리한 뒤 예외를 던진다 (BR-5, AC-8).</p>
     *
     * @param groupId     핀 소속 그룹 id
     * @param pinId       대상 핀 id
     * @param imageBytes  검증 완료된 원본 이미지 bytes
     * @param contentType 원본 contentType (image/jpeg | image/png | image/webp)
     * @return 저장된 원본/썸네일 객체 키 쌍
     */
    StoredPhoto store(Long groupId, Long pinId, byte[] imageBytes, String contentType);

    /**
     * best-effort 삭제. 실패는 로그만 남기고 예외를 전파하지 않는다 (고아 객체는 무해, FR-PIN-10b).
     * <p>교체 시 기존 키 회수 / 삭제 시 현재 키 회수에 사용한다. null 키는 무시한다.</p>
     */
    void deleteQuietly(String photoKey, String thumbnailKey);

    /**
     * 저장된 원본/썸네일 S3 객체 키 쌍 (uuid 공유).
     */
    record StoredPhoto(String photoKey, String thumbnailKey) {
    }
}
