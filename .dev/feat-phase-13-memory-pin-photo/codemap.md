## 코드 맵: Phase 13 추억핀 사진 업로드 (MEMORY 핀 한정 사진 1장)

### 핵심 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/Pin.java → 핀 엔티티. `applyPhoto(key, thumbKey, uploaderId)`/`clearPhoto()` 추가 대상 (applyManualMemo/clearMemo 패턴 답습)
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinService.java → 핀 서비스. 사진 업로드/삭제 + MEMORY 검증 추가 대상
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinSummary.java → 핀 요약 도메인 객체. `photoUrl`/`photoThumbnailUrl`(nullable) 추가 대상
- frontend/src/lib/api/http.ts → 전송 계층. FormData면 Content-Type JSON 강제 안 함 분기 추가
- frontend/src/app/map/_components/MemoTagPanelContent.tsx → 신규 등록 패널. tag=MEMORY일 때 업로더 노출 (POST→pinId→업로드 2-step)

### 참조 파일
- backend/apps/wherewego-api/src/main/java/com/wherewego/domain/pin/PinRepository.java → 도메인 포트
- frontend/src/app/map/_components/VisitMemoSheet.tsx → 방문→추억 전환 시 업로더 재사용
- frontend/src/app/map/_components/PinPopup.tsx → 핀 수정 시 사진 추가/교체/삭제 진입점
- frontend/src/components/ui/SpeechBubblePopup.tsx → 말풍선 메모 우측 원형 썸네일 + 클릭 시 원본 뷰어
- frontend/src/lib/api/pin.ts → uploadPinPhoto/deletePinPhoto 추가 대상
- frontend/src/app/map/actions.ts → uploadPinPhotoAction/deletePinPhotoAction 서버 액션 추가
- frontend/src/lib/api/types.ts → PinSummaryResponse에 photoUrl?/photoThumbnailUrl? 추가

### 설정
- backend/apps/wherewego-api/build.gradle.kts → AWS SDK v2 `software.amazon.awssdk:s3` 의존성 추가 (현재 S3 전무)
- backend/apps/wherewego-api/src/main/resources/application.yml → `wherewego.s3.bucket/region/public-base-url` + `spring.servlet.multipart.max-file-size` 설정 추가
- backend/apps/wherewego-api/src/main/resources/db/migration/ → 최신 V012, 신규 `V013__add_pins_photo.sql` (nullable 컬럼 4개)

### 설계 단계 추가 발견 (기존 파일)
- backend/.../interfaces/api/pin/PinV1Controller.java → 멀티파트 엔드포인트 2개 추가 대상 (`@RequestMapping("/api/v1/groups")`)
- backend/.../interfaces/api/pin/PinV1Dto.java → PinSummaryResponse record에 photoUrl/photoThumbnailUrl 추가
- backend/.../support/error/ErrorType.java → 신규 에러코드 PIN_PHOTO_* 추가 (PIN_*/PLC_* 접두어 컨벤션)
- backend/.../interfaces/api/ApiControllerAdvice.java → MaxUploadSizeExceededException 핸들러 추가 검토 (현재 미존재)
- backend/.../WherewegoApiApplication.java → @ConfigurationPropertiesScan (S3Properties record만 작성 시 자동 빈 등록, MapboxProperties 패턴)
- frontend/src/lib/api/http-client.ts → 클라 전용 전송 계층 (FormData 분기 대상, http.ts와 별개)
- frontend/src/app/map/MapClient.tsx → 업로더 3곳 상위 위임 핸들러 주입 지점 (handlePinCreated/handleVisitMemoSave/PinPopup), reducer update로 마커 캐시 유지
