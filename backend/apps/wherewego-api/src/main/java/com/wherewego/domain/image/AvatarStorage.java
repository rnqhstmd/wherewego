package com.wherewego.domain.image;

/**
 * 아바타(그룹 대표 이미지 · 사용자 프로필 사진) 저장 포트 (GP-1). 헥사고날 아키텍처의 도메인 포트이며,
 * 구현체(어댑터)는 {@code infrastructure.image} 의 S3 기반 {@code S3AvatarStorage} 이다.
 *
 * <p>핀 사진의 {@code PinPhotoStorage} 를 일반화한 계약이다. 핀은 {@code (groupId, pinId)} 로 키 경로가
 * 고정돼 전용 시그니처를 갖지만, 아바타는 그룹/사용자 양쪽에서 쓰이므로 호출자가 키 prefix
 * (예: {@code groups/3/avatar}, {@code users/7/avatar})를 직접 지정한다.</p>
 *
 * <p>검증된 원본 bytes 를 입력으로 받아 썸네일 생성 → 원본·썸네일 2객체 저장까지 담당한다.
 * 키 스킴/캐시 헤더/원자성 등 스토리지 세부는 어댑터 책임이며, 서비스는 본 포트만 의존한다.</p>
 */
public interface AvatarStorage {

    /**
     * 검증된 원본 bytes → 썸네일 생성 → 원본·썸네일 put.
     * <p>부분 실패(원본 성공 후 썸네일 실패 등) 시 이미 업로드한 객체를 정리한 뒤 예외를 던진다(핀 사진 BR-5/AC-8 동치).</p>
     *
     * @param keyPrefix   객체 키 prefix (예: {@code groups/3/avatar} / {@code users/7/avatar}) — 끝 슬래시 없음
     * @param imageBytes  검증 완료된 원본 이미지 bytes
     * @param contentType 원본 contentType (image/jpeg | image/png | image/webp)
     * @return 저장된 원본/썸네일 객체 키 쌍
     */
    StoredAvatar store(String keyPrefix, byte[] imageBytes, String contentType);

    /**
     * best-effort 삭제. 실패는 로그만 남기고 예외를 전파하지 않는다(고아 객체는 무해).
     * <p>교체 시 기존 키 회수 / 삭제 시 현재 키 회수에 사용한다. null 키는 무시한다.</p>
     */
    void deleteQuietly(String imageKey, String thumbKey);

    /**
     * 저장된 원본/썸네일 S3 객체 키 쌍 (uuid 공유).
     */
    record StoredAvatar(String imageKey, String thumbKey) {
    }
}
