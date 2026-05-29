# 최종 설계서: Phase 13 — 추억핀 사진 업로드

> 상태: 확정 (design-critic 검토 통과 + 사용자 Q&A 반영)

## 설계 규모

**대형** — AWS SDK 최초 도입(S3 신규 통합) + 멀티파트 엔드포인트 신규 + V013 마이그레이션 + 백엔드 풀스택(엔티티/포트/어댑터/서비스/DTO/컨트롤러/예외) + 프론트 신규 컴포넌트 2개 + 전송 계층 분기 + 업로더 3곳 연결 + next.config 상향. 기존 코드에 멀티파트/외부 스토리지 사례 전무.

## 배경 및 목적

핀 상세(말풍선)는 현재 텍스트 메모만 저장한다. MEMORY(추억) 핀에 한정해 사진 1장을 선택적으로 첨부하고, 말풍선 원형 썸네일·원본 뷰어로 열람한다. 4인 규모에서 AWS S3 프리티어(5GB / GET 2만 / PUT·LIST 2천 / 전송 100GB) 안에서 무과금 운영을 전제로 한다. 사진은 MEMORY 전용이며 REEL/WISH에는 업로드 UI를 노출하지 않는다.

## 요구사항 및 수용 기준

PRD `.dev/feat-phase-13-memory-pin-photo/prd.md`를 그대로 사용한다. 핵심 인용:

- `[Must] FR-PIN-9a`: V013 마이그레이션 — nullable 컬럼 4개(`photo_key`, `photo_thumbnail_key`, `photo_uploaded_by`, `photo_uploaded_at`). DB엔 S3 키만 저장.
- `[Must] FR-PIN-9b/c/d`: `POST .../photo` — 검증(MEMORY/타입/크기/픽셀) → 썸네일 생성(장변 ~256px WebP) → S3 2객체 저장 → 갱신 `PinSummaryResponse` 반환. 원본은 장변 ~1600px JPEG.
- `[Must] FR-PIN-9e/f`: 키 스킴 `pins/{groupId}/{pinId}/{uuid}.jpg` / `..._thumb.webp`, `Cache-Control: public, max-age=31536000, immutable`, 공개 버킷 + UUID, URL = `{public-base-url}/{key}`.
- `[Must] FR-PIN-10a/b`: `DELETE .../photo` — S3 2객체 삭제 + 4필드 초기화. 교체는 재 POST(기존 객체 best-effort 삭제).
- `[Must] FR-PIN-11a/b`: 말풍선 메모 우측 원형 썸네일(~44px, lazy), 클릭 시 `PinPhotoViewer` 슬라이드 오픈 + blur-up.
- `[Must] FR-PIN-9g~k`: 공용 `PinPhotoUploader` 3곳 재사용(신규 등록 2-step 포함), 클라이언트 canvas 압축.
- `[Must] FR-PIN-9l/m`: `PinSummaryResponse`에 `photoUrl`/`photoThumbnailUrl`(조합 URL) 추가, AWS SDK v2 + `wherewego.s3.*` 설정.
- `[Must] BR-1~8`: MEMORY 서비스 검증, 태그 이탈 시 사진 보존(BR-3), 활성 멤버 권한(BR-4), S3 업로드 원자성(BR-5), 메모/사진 독립(BR-6), UUID 키(BR-7), LIST 미사용(BR-8).

수용 기준 AC-1 ~ AC-17은 PRD 표 그대로 적용한다.

## 변경 범위

**영향 모듈/패키지:**
- 백엔드: `com.wherewego.domain.pin`, `com.wherewego.infrastructure.pin`, `com.wherewego.interfaces.api`(컨트롤러/DTO/advice), `com.wherewego.config.env`/`config.s3`, `com.wherewego.support.error`, `db/migration`, `build.gradle.kts`, `application.yml`
- 프론트: `src/lib/api`, `src/lib/image`(신규), `src/app/map`, `src/components/ui`, `next.config.ts`, `package.json`

**신규 생성 파일 (백엔드 6 + 프론트 3 + 문서 1 = 10):**
- `backend/apps/wherewego-api/src/main/resources/db/migration/V013__add_pins_photo.sql`
- `backend/.../domain/pin/PinPhotoStorage.java` (포트 인터페이스 + 내부 `StoredPhoto` record)
- `backend/.../infrastructure/pin/S3PinPhotoStorage.java` (어댑터 구현)
- `backend/.../config/env/S3Properties.java`
- `backend/.../config/s3/S3Config.java` (`S3Client` 빈)
- (선택) `backend/.../interfaces/api/pin/PinPhotoV1ApiSpec.java` — 기존 `PinV1ApiSpec` 확장으로 갈음 가능
- `frontend/src/app/map/_components/PinPhotoUploader.tsx`
- `frontend/src/app/map/_components/PinPhotoViewer.tsx`
- `frontend/src/lib/image/compressImage.ts`
- `docs/` S3 버킷 프로비저닝 가이드 (Should, FR-PIN-9n)

**수정 대상 파일 (백엔드 9 + 프론트 9 = 18):**
- `backend/.../domain/pin/Pin.java` (필드 4 + applyPhoto/clearPhoto/hasPhoto)
- `backend/.../domain/pin/PinSummary.java` (photoUrl/photoThumbnailUrl + from 시그니처)
- `backend/.../domain/pin/PinService.java` (uploadPhoto/deletePhoto + toSummary URL 조합)
- `backend/.../interfaces/api/pin/PinV1Controller.java` + `PinV1ApiSpec.java` (멀티파트 엔드포인트 2개)
- `backend/.../interfaces/api/pin/PinV1Dto.java` (PinSummaryResponse 필드 2)
- `backend/.../interfaces/api/ApiControllerAdvice.java` (**`MaxUploadSizeExceededException` 핸들러 필수**)
- `backend/.../support/error/ErrorType.java` (신규 에러코드 6개)
- `backend/apps/wherewego-api/build.gradle.kts` (AWS SDK + scrimage-webp)
- `backend/apps/wherewego-api/src/main/resources/application.yml` (**multipart 설정 필수** + wherewego.s3.*)
- `frontend/src/lib/api/http.ts` (**apiFetchServer FormData 분기 필수**) + `http-client.ts` (동일 분기)
- `frontend/src/lib/api/pin.ts` (uploadPinPhoto/deletePinPhoto)
- `frontend/src/lib/api/types.ts` (photoUrl?/photoThumbnailUrl?)
- `frontend/src/app/map/actions.ts` (서버 액션 2개)
- `frontend/src/app/map/_components/MemoTagPanelContent.tsx` (2-step)
- `frontend/src/app/map/_components/VisitMemoSheet.tsx` (즉시 업로드)
- `frontend/src/app/map/_components/PinPopup.tsx` (메모 탭 하단 업로더 + 썸네일/뷰어 연결)
- `frontend/src/components/ui/SpeechBubblePopup.tsx` (**경로 정정**: `_components` 아님. 메모 우측 썸네일 slot)
- `frontend/src/app/map/MapClient.tsx` (업로드/삭제 핸들러 위임 주입)
- `frontend/next.config.ts` (**`experimental.serverActions.bodySizeLimit` 상향 필수**)
- `frontend/package.json` (`browser-image-compression`)

집계: 신규 10, 수정 18 (총 28개 산출물 대상).

## 적용 컨벤션

- 백엔드 `CLAUDE.md`/`conventions.md` 미발견. 프론트 `frontend/AGENTS.md` 존재 — **이 Next.js는 학습 데이터와 다를 수 있으므로 코드 작성 전 `node_modules/next/dist/docs/`의 관련 가이드를 확인**(특히 Server Actions bodySizeLimit, FormData 전달).
- **레이어(헥사고날)**: `domain`(엔티티+포트+서비스) / `infrastructure`(어댑터 `@Component implements 포트`) / `interfaces.api`(컨트롤러 `implements *ApiSpec` + `*Dto` static record). 예: `PinRepository`(포트) ↔ `PinRepositoryImpl`(어댑터) ↔ `PinJpaRepository`.
- **네이밍**: 도메인 메서드 동사형(`applyManualMemo`/`clearMemo`/`changeTag`). 컬럼 snake_case + `@Column(name=...)`. DTO는 `PinV1Dto` 내부 정적 record + `from(...)` 정적 팩토리.
- **DI/Config**: `@RequiredArgsConstructor` + `final`. Properties는 `record` + `@ConfigurationProperties` + `@Validated`. `WherewegoApiApplication`에 `@ConfigurationPropertiesScan` 있어 **별도 `@EnableConfigurationProperties` 불필요**.
- **에러 처리**: `throw new CoreException(ErrorType.XXX)` → `ApiControllerAdvice`가 `ApiResponse.fail` 변환. `ErrorType` enum에 `PIN_*` 접두어 + HttpStatus.
- **트랜잭션**: 서비스 `@Transactional`, PATCH/DELETE는 `findActiveByIdAndGroupIdForUpdate`(PESSIMISTIC_WRITE). 멤버십은 `groupMemberService.requireActiveMembership(userId, groupId)`(실패 시 `GROUP_NOT_MEMBER` 403).
- **PinSummary 변환**: `PinService.toSummary(Pin)`/`toSummaries(List)`가 닉네임 주입 + record 생성 단일 지점. **URL 조합도 여기서 수행**.
- **프론트**: `lib/api/http.ts::apiFetchServer`(Server Component/Action 전용, 쿠키 화이트리스트 포워딩) ↔ `http-client.ts::apiFetch`(클라 전용). actions.ts는 `"use server"` + `ApiError` catch → `{ ok, code, message }`. `/map`은 `revalidatePath` 미호출(클라 state 우선), `/pins`만 try/catch revalidate. 스타일은 인라인 `style` + `colors`/`fonts` 토큰.

## 상세 설계

### 백엔드

**1. `db/migration/V013__add_pins_photo.sql` (신규)**
- 최신 V012. `ALTER TABLE pins ADD COLUMN IF NOT EXISTS` 멱등 패턴(V010). nullable 컬럼 4개 + COMMENT.
```sql
ALTER TABLE pins
    ADD COLUMN IF NOT EXISTS photo_key TEXT NULL,
    ADD COLUMN IF NOT EXISTS photo_thumbnail_key TEXT NULL,
    ADD COLUMN IF NOT EXISTS photo_uploaded_by BIGINT NULL,
    ADD COLUMN IF NOT EXISTS photo_uploaded_at TIMESTAMPTZ NULL;
```
- `photo_uploaded_by`는 기존 `created_by`/`memo_updated_by` 컨벤션대로 컬럼만(명시 FK 없음). 인덱스 불필요.

**2. `domain/pin/Pin.java` (수정)** — 필드 4 + 도메인 메서드 3
```java
public void applyPhoto(String photoKey, String thumbnailKey, Long uploaderId)  // 4필드 set + photoUploadedAt = ZonedDateTime.now()
public void clearPhoto()      // 4필드 null
public boolean hasPhoto()     // photoKey != null (교체 시 기존 키 회수 분기)
```
- `@Column(name=...)` 필드 4개. MEMORY 검증은 서비스 책임(도메인은 단순 위임).

**3. `domain/pin/PinPhotoStorage.java` (신규 포트)**
```java
public interface PinPhotoStorage {
    /** 검증된 원본 bytes → 픽셀 상한 검증 → 썸네일 생성 → 원본·썸네일 put. 부분 실패 시 업로드분 정리 후 예외(BR-5, AC-8). */
    StoredPhoto store(Long groupId, Long pinId, byte[] imageBytes, String contentType);
    /** best-effort 삭제. 실패는 로그만(고아 무해, FR-PIN-10b). */
    void deleteQuietly(String photoKey, String thumbnailKey);
    record StoredPhoto(String photoKey, String thumbnailKey) {}
}
```

**4. `config/env/S3Properties.java` + `config/s3/S3Config.java` (신규)**
```java
@Validated @ConfigurationProperties(prefix = "wherewego.s3")
public record S3Properties(@NotBlank String bucket, @NotBlank String region, @NotBlank String publicBaseUrl) {}
```
- `S3Config`: `@Bean S3Client`, 자격증명 `DefaultCredentialsProvider`(운영 EC2 IAM Role / 로컬 .env·프로필). **짧은 타임아웃 필수**(Q3 — 락+커넥션 장기 점유 방지):
```java
S3Client.builder()
    .region(Region.of(props.region()))
    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5))
                                 .apiCallAttemptTimeout(Duration.ofSeconds(3)))
    .httpClientBuilder(ApacheHttpClient.builder()
        .connectionTimeout(Duration.ofSeconds(3))
        .socketTimeout(Duration.ofSeconds(5)))
    .build();
```

**5. `infrastructure/pin/S3PinPhotoStorage.java` (신규 어댑터)** — `@Component implements PinPhotoStorage`
- UUID 키 생성 → 디코딩 + **픽셀 상한 검증** → 썸네일 WebP 인코딩 → `putObject`(원본+썸네일, Cache-Control) → 부분 실패 정리.
- 썸네일 라이브러리(Q1 확정): **`com.sksamuel.scrimage:scrimage-webp`**. `ImmutableImage.loader().fromBytes(bytes)` → `.max(256,256)` → `WebpWriter` 인코딩.
- **픽셀 상한(Q2 확정)**: 디코딩 직후 장변 검증, **장변 4096px 초과 시 `CoreException(PIN_PHOTO_DIMENSION_EXCEEDED)`**. decompression bomb 방지 위해 가능하면 `ImageIO.getImageReaders`로 헤더 차원 선확인 후 full 디코딩. 검증은 store 1회 디코딩과 공유.
- 키: 원본/썸네일 동일 `uuid` 공유 — `pins/%d/%d/%s.jpg`, `pins/%d/%d/%s_thumb.webp`(교체 시 한 쌍 회수, BR-7).
- `PutObjectRequest`에 `.cacheControl("public, max-age=31536000, immutable")` + `.contentType(...)`(원본=요청 타입, 썸네일=`image/webp`)(AC-6/AC-7).
- **원자성(BR-5/AC-8)**: 원본 put 성공 후 썸네일 인코딩/put 실패 시 원본 `deleteObject` 후 예외 재throw.
- S3 완전 실패(Q4 확정): `SdkException` 류 → `CoreException(PIN_PHOTO_STORAGE_FAILED)` 래핑.

**6. `domain/pin/PinService.java` (수정)** — 유스케이스 2개 + URL 조합
```java
@Transactional
public PinSummary uploadPhoto(Long userId, Long groupId, Long pinId, byte[] imageBytes, String contentType)
@Transactional
public PinSummary deletePhoto(Long userId, Long groupId, Long pinId)
```
- `uploadPhoto`: `requireActiveMembership`(BR-4) → `findActiveByIdAndGroupIdForUpdate`(없으면 `PIN_NOT_FOUND`) → MEMORY 검증(`tag != MEMORY` → `PIN_PHOTO_NOT_MEMORY`, BR-1/AC-3) → 기존 키 백업(`hasPhoto()`) → **`storage.store(...)`(트랜잭션 내, Q3)** → `pin.applyPhoto(newKeys)` → 기존 키 있으면 `storage.deleteQuietly(oldKeys)`(best-effort, FR-PIN-10b/AC-10) → `toSummary(pin)`.
  - **Q3 트랜잭션 경계**: S3 I/O를 `@Transactional` 내 유지(단순·정합). S3Client 짧은 타임아웃으로 락 장기 점유 방지.
  - **수용된 리스크**: `applyPhoto` 후 커밋 실패 시 S3 객체가 고아로 남을 수 있으나 PRD 비목표(고아 자동 정리 없음)·프리티어상 무해 — 이는 BR-5(원본 성공+썸네일 실패의 능동 정리)와 **다른 경로**이므로 구현자는 혼동하지 말 것.
- `deletePhoto`: 멤버십 → 락 조회 → 키 백업 → `pin.clearPhoto()`(AC-9) → `storage.deleteQuietly(keys)` → `toSummary(pin)`.
- `toSummary`/`toSummaries`: URL 조합 헬퍼 `toPublicUrl(key) = key == null ? null : props.publicBaseUrl() + "/" + key`.
- **비-MEMORY 응답 계약**: `toSummary`는 키가 있으면 **tag 무관하게** photoUrl/photoThumbnailUrl 조합(단순성). MEMORY 게이트는 프론트 UI 책임. 공유카드 `frontend/src/lib/share/renderPinCard.ts`는 photoUrl 미참조 → 현재 오용 위험 없음(BR-3 일관).

**7. `domain/pin/PinSummary.java` (수정)** — record에 `photoUrl`/`photoThumbnailUrl` + `from(...)` 시그니처 확장(호출처 2곳만).

**8. `interfaces/api/pin/PinV1Dto.java` (수정)** — `PinSummaryResponse`에 필드 2 + `from` 매핑. `UpdatePinResponse`/`PinListResponse` 자동 전파.

**9. `interfaces/api/pin/PinV1Controller.java` + `PinV1ApiSpec.java` (수정)** — 멀티파트 2개
```java
@PostMapping(value = "/{groupId}/pins/{pinId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ApiResponse<PinV1Dto.PinSummaryResponse> uploadPinPhoto(
    @AuthUser Long userId, @PathVariable Long groupId, @PathVariable Long pinId,
    @RequestParam("file") MultipartFile file)

@DeleteMapping("/{groupId}/pins/{pinId}/photo")
public ApiResponse<PinV1Dto.PinSummaryResponse> deletePinPhoto(
    @AuthUser Long userId, @PathVariable Long groupId, @PathVariable Long pinId)
```
- 컨트롤러 검증(서비스 전): null/empty → `PIN_PHOTO_FILE_REQUIRED`; contentType이 `image/jpeg|png|webp` 아니면 `PIN_PHOTO_TYPE_INVALID`(AC-5); `getSize() > 2MB`이면 `PIN_PHOTO_SIZE_EXCEEDED`(AC-4). 픽셀 상한은 어댑터. `file.getBytes()` → `pinService.uploadPhoto(...)`.
- DELETE는 갱신 summary 반환(204 아님).

**10. `interfaces/api/ApiControllerAdvice.java` (수정, MUST)** — 멀티파트 크기 초과 핸들러
```java
@ExceptionHandler(MaxUploadSizeExceededException.class)
public ResponseEntity<ApiResponse<?>> handle(MaxUploadSizeExceededException e) {
    return failureResponse(ErrorType.PIN_PHOTO_SIZE_EXCEEDED, null);
}
```

**11. `support/error/ErrorType.java` (수정)** — 신규 에러코드 6개 (`PIN_*`)
```java
PIN_PHOTO_NOT_MEMORY(BAD_REQUEST, "PIN_PHOTO_NOT_MEMORY", "사진은 추억(MEMORY) 핀에만 첨부할 수 있어요.")          // BR-1, AC-3
PIN_PHOTO_TYPE_INVALID(BAD_REQUEST, "PIN_PHOTO_TYPE_INVALID", "JPEG, PNG, WebP 이미지만 업로드할 수 있어요.")        // AC-5
PIN_PHOTO_SIZE_EXCEEDED(BAD_REQUEST, "PIN_PHOTO_SIZE_EXCEEDED", "사진은 2MB 이하만 업로드할 수 있어요.")            // AC-4
PIN_PHOTO_DIMENSION_EXCEEDED(BAD_REQUEST, "PIN_PHOTO_DIMENSION_EXCEEDED", "사진 해상도가 너무 커요. (장변 4096px 이하)")  // Q2
PIN_PHOTO_FILE_REQUIRED(BAD_REQUEST, "PIN_PHOTO_FILE_REQUIRED", "업로드할 사진 파일이 없어요.")
PIN_PHOTO_STORAGE_FAILED(BAD_GATEWAY, "PIN_PHOTO_STORAGE_FAILED", "사진 저장에 실패했어요. 잠시 후 다시 시도해 주세요.")  // Q4
```

**12. `build.gradle.kts` (수정)**
```kotlin
implementation(platform("software.amazon.awssdk:bom:<version>"))
implementation("software.amazon.awssdk:s3")
implementation("software.amazon.awssdk:apache-client")  // S3Config ApacheHttpClient 타임아웃용
implementation("com.sksamuel.scrimage:scrimage-core:<version>")
implementation("com.sksamuel.scrimage:scrimage-webp:<version>")
```

**13. `application.yml` (수정, MUST)** — multipart + S3
```yaml
spring:
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 5MB
wherewego:
  s3:
    bucket: ${S3_BUCKET}
    region: ${S3_REGION:ap-northeast-2}
    public-base-url: ${S3_PUBLIC_BASE_URL}
```

### 프론트엔드

**14. `next.config.ts` (수정, MUST)** — Server Action bodySizeLimit 상향
- Next.js Server Action 기본 bodySizeLimit 1MB → 2MB 압축 파일 거부. 4MB로 상향(여유). **`frontend/AGENTS.md` 경고대로 작성 전 `node_modules/next/dist/docs/`에서 현 버전(16.2.6) `serverActions` 설정 키 확인**.
```ts
experimental: { serverActions: { bodySizeLimit: "4mb" } }
```

**15. `lib/api/http.ts` (수정, MUST) + `http-client.ts` (수정)** — FormData 분기
- 현재 두 함수 모두 `headers`에 `"Content-Type": "application/json"` 무조건 부착. 변경: `init?.body instanceof FormData`이면 미부착 → 브라우저/fetch가 boundary 자동(AC-17). http.ts `apiFetchServer`가 이번 경로 필수. 2-hop(브라우저→Next→백엔드) 수용.
```ts
const isFormData = init?.body instanceof FormData;
headers: {
  ...(isFormData ? {} : { "Content-Type": "application/json" }),
  Cookie: cookieHeader,
  ...(init?.headers ?? {}),
}
```

**16. `lib/api/types.ts` (수정)** — `PinSummaryResponse`에 `photoUrl: string | null` / `photoThumbnailUrl: string | null` 추가. 하위 호환.

**17. `lib/api/pin.ts` (수정)**
```ts
export async function uploadPinPhoto(groupId, pinId, file: File): Promise<PinSummaryResponse>  // FormData append("file") → POST
export async function deletePinPhoto(groupId, pinId): Promise<PinSummaryResponse>              // DELETE → 갱신 summary
```

**18. `app/map/actions.ts` (수정)** — 서버 액션 2개 (FormData 수령, Q5 확정)
```ts
export type UploadPinPhotoActionResult =
  | { ok: true; data: PinSummaryResponse }
  | { ok: false; code: string; message: string };
export async function uploadPinPhotoAction(groupId, pinId, formData: FormData): Promise<UploadPinPhotoActionResult>
export async function deletePinPhotoAction(groupId, pinId): Promise<UploadPinPhotoActionResult>
```
- `formData.get("file")` → `uploadPinPhoto` 위임. `/map`은 revalidate 미호출.

**19. `lib/image/compressImage.ts` (신규)** — `browser-image-compression`(Q6): 장변 1600px, JPEG ~0.8, EXIF 방향 자동 보정, useWebWorker. 결과 MIME JPEG 강제(AC-16).
```ts
export async function compressPinPhoto(file: File): Promise<File>
```

**20. `app/map/_components/PinPhotoUploader.tsx` (신규 공용)**
```ts
interface PinPhotoUploaderProps {
  photoUrl?: string | null;
  thumbnailUrl?: string | null;
  onFileSelected: (file: File) => void | Promise<void>;  // 압축 후 파일 전달
  onDelete?: () => void | Promise<void>;
  uploading?: boolean;
  disabled?: boolean;
}
```
- 단일 책임: `<input type="file" accept="image/*">`(모바일 카메라) → 선택 시 `compressPinPhoto` → 미리보기(object URL) → `onFileSelected`. 진행 중 표시 + 삭제. **업로드 API 호출은 호출처 책임**(즉시 vs 2-step 상이).

**21. `app/map/_components/MemoTagPanelContent.tsx` (수정)** — 신규 등록 2-step
- `tag === "MEMORY"`일 때 메모 textarea 아래 `<PinPhotoUploader onFileSelected={f => setPendingPhoto(f)} />`. `handleSave`:
```
createPinAction 성공 → pendingPhoto 있으면 FormData로 uploadPinPhotoAction(groupId, result.data.id, form):
  성공 → onSuccess(uploadResult.data)             // photoUrl 포함 갱신 summary
  실패 → onSuccess(result.data) + 토스트(AC-14)    // 핀은 저장, 사진은 수정에서 재시도 안내
```
- 사진 실패가 핀 생성을 무효화하지 않음(BR-6). 비-MEMORY엔 업로더 미노출.

**22. `app/map/_components/VisitMemoSheet.tsx` (수정)** — 방문→추억 즉시 업로드
- textarea 아래 `<PinPhotoUploader photoUrl={pin.photoUrl} thumbnailUrl={pin.photoThumbnailUrl} onFileSelected={...} onDelete={...} />`. 업로드/삭제는 **MapClient 주입 핸들러**(`onPhotoUpload`/`onPhotoDelete`) 위임. 메모 저장과 독립(BR-6).

**23. `app/map/_components/PinPopup.tsx` (수정)** — 메모 탭 하단 업로더(Q7 확정)
- **새 탭 생성 안 함. 3탭(place/tag/memo) 유지.** memo 탭 패널 하단(`PinPopupMemoEditor` 아래)에 `pin.tag === "MEMORY"`일 때만 `<PinPhotoUploader .../>` 배치. 업로드/삭제는 MapClient 주입 핸들러 위임.
- 말풍선 썸네일 연결: `SpeechBubblePopup`에 `pin.tag === "MEMORY" && pin.photoThumbnailUrl`일 때 썸네일 노드 + 클릭 시 `PinPhotoViewer` 오픈 state 전달.

**24. `components/ui/SpeechBubblePopup.tsx` (수정, 경로 정정)** — 메모 우측 썸네일 slot
- **정확한 경로**: `frontend/src/components/ui/SpeechBubblePopup.tsx`(코드 맵 `_components`는 오기).
- 신규 prop `memoThumbnail?: ReactNode` → 메모 블록을 flex row로 감싸 **우측에 ~44px 원형 이미지**(PRD "메모 우측"). `null`이면 미렌더 → 레이아웃 불변(AC-11).
```tsx
<img loading="lazy" style={{width:44,height:44,borderRadius:"50%",objectFit:"cover",cursor:"pointer"}} onClick={openViewer} />
```

**25. `app/map/_components/PinPhotoViewer.tsx` (신규)** — 원본 뷰어
- props: `{ thumbnailUrl: string; photoUrl: string; onClose: () => void; }`.
- 옆 슬라이드 오픈(CSS transform). **blur-up**: 썸네일 `filter: blur(12px)` 배경, 원본 `<img onLoad={()=>setLoaded(true)}>` 완료 시 opacity 전환(QE-3, 스피너 없음). 닫기 backdrop/X/스와이프. 캐시 썸네일 재사용 → 추가 GET 0.

**26. `app/map/MapClient.tsx` (수정)** — 핸들러 위임
- `handlePhotoUpload`/`handlePhotoDelete` 추가 → `uploadPinPhotoAction`/`deletePinPhotoAction` 호출 후 반환 summary로 reducer **update dispatch**(마커 캐시 유지, revalidate 미호출). PinPopup·VisitMemoSheet에 prop 주입. 신규 등록은 `MemoTagPanelContent`가 2-step 자체 처리 후 `onSuccess`로 최종 summary 전달.

## 의존성 및 영향도

**새 의존성**: 백엔드 `software.amazon.awssdk:s3`(+`apache-client`, BOM), `com.sksamuel.scrimage:scrimage-core`/`scrimage-webp`. 프론트 `browser-image-compression`.

**기존 코드 영향**: `PinSummary.from` 시그니처 변경 → 호출처 2곳만. `PinSummaryResponse` 필드 추가 → 응답 하위 호환(소비처 무시, `renderPinCard.ts` 미참조 확인). 헤더 분기 → JSON 호출 동작 불변. multipart·serverActions 설정 추가 → 기존 JSON 무영향.

**하위 호환**: V013 nullable(AC-2). REEL/WISH 업로더 미노출·말풍선 불변. 태그 이탈 시 보존(BR-3).

## 구현 순서 (백엔드 → 프론트, 28단계)

```
1.  V013 마이그레이션 SQL (의존: 없음)
2.  ErrorType 신규 에러코드 6개 (의존: 없음)
3.  build.gradle.kts AWS SDK + scrimage-webp (의존: 없음)
4.  application.yml multipart 설정 + wherewego.s3.* (의존: 없음)   ← MUST
5.  S3Properties + S3Config(짧은 타임아웃) (의존: 3, 4)
6.  Pin.java 필드 4 + applyPhoto/clearPhoto/hasPhoto (의존: 1)
7.  PinPhotoStorage 포트 + StoredPhoto record (의존: 없음)
8.  S3PinPhotoStorage 어댑터(픽셀상한 4096·WebP·원자성·STORAGE_FAILED) (의존: 2, 5, 7)
9.  PinSummary photoUrl/photoThumbnailUrl + from 시그니처 (의존: 없음)
10. PinService uploadPhoto/deletePhoto + toSummary URL 조합 (의존: 5, 6, 7, 9)
11. PinV1Dto PinSummaryResponse 필드 2 (의존: 9)
12. PinV1Controller/ApiSpec 멀티파트 엔드포인트 2개 (의존: 2, 10, 11)
13. ApiControllerAdvice MaxUploadSizeExceededException 핸들러 (의존: 2)   ← MUST
14. (Should) S3 버킷 프로비저닝 가이드 문서 (의존: 없음)
15. next.config.ts serverActions.bodySizeLimit 4mb (의존: 없음)   ← MUST
16. package.json browser-image-compression (의존: 없음)
17. types.ts photoUrl/photoThumbnailUrl (의존: 없음)
18. http.ts + http-client.ts FormData 분기 (의존: 없음)   ← MUST
19. compressImage.ts 압축 유틸 (의존: 16)
20. pin.ts uploadPinPhoto/deletePinPhoto (의존: 17, 18)
21. actions.ts uploadPinPhotoAction/deletePinPhotoAction (의존: 20)
22. PinPhotoUploader 컴포넌트 (의존: 19)
23. PinPhotoViewer 컴포넌트 (의존: 17)
24. SpeechBubblePopup 메모 우측 썸네일 slot (components/ui) (의존: 없음)
25. MemoTagPanelContent 2-step 연결 (의존: 21, 22)
26. VisitMemoSheet 업로더 연결 (의존: 21, 22)
27. PinPopup 메모 탭 하단 업로더 + 썸네일/뷰어 연결 (의존: 21, 22, 23, 24)
28. MapClient 핸들러 위임 주입 (의존: 25, 26, 27)
```

**병렬 배치**: 백엔드 초기 1,2,3,4,7,9 동시. 프론트 초기 14,15,16,17,24 동시. 12·13은 다른 파일이라 병렬 가능. 프론트 25·26·27은 22 완료 후 병렬(27은 24도 의존).

## 테스트 전략

**백엔드:**
- `S3PinPhotoStorage` 단위(S3Client 목): 키 스킴(`.jpg`/`_thumb.webp`), PutObjectRequest 캡처로 Cache-Control(AC-6)·썸네일 contentType `image/webp` 검증, WebP 생성, 픽셀 4096 초과 `PIN_PHOTO_DIMENSION_EXCEEDED`, 부분 실패 시 원본 deleteObject(AC-8). 소형 PNG/JPEG fixture.
- `PinService` IT(포트 목): MEMORY 검증(AC-3), 비활성 403(AC-15), 교체 시 기존 키 deleteQuietly(AC-10), deletePhoto 후 4필드 null(AC-9), 태그 이탈 보존(AC-13), STORAGE_FAILED 전파.
- 컨트롤러 IT(MockMultipartFile + `@MockBean` 포트): 타입 통과/거부(AC-5), 크기 거부(AC-4), 멀티파트 한도 초과 advice 매핑, 응답 URL non-null(AC-1)·조합(AC-7).
- `PinV1DtoTest`: `PinSummaryResponse.from` URL 매핑.

**프론트(Vitest):**
- `compressImage`(상한·JPEG·방향), `PinPhotoUploader`(선택→onFileSelected/삭제), `PinPhotoViewer`(blur-up/닫기), `MemoTagPanelContent`(2-step AC-14), `SpeechBubblePopup`(썸네일 조건부 AC-11).

## 배포 주의

- **scrimage-webp libwebp 네이티브 바이너리**: WebP 인코딩에 번들된 libwebp 네이티브 바이너리 사용. **EC2(Linux) 첫 배포 시 실제 업로드 1건으로 네이티브 로딩 성공 1회 검증**(로컬 OS와 EC2 아키텍처 차이로 로딩 실패 가능). 실패 시 `UnsatisfiedLinkError` → 컨테이너/JVM 임시 디렉토리 쓰기 권한 확인.
- **S3 버킷 프로비저닝(FR-PIN-9n)**: `pins/*`에 `s3:GetObject` 공개 허용 + "퍼블릭 액세스 차단(정책)" 해제, EC2 IAM Role에 `s3:PutObject`/`s3:DeleteObject`, `public-base-url`을 버킷 URL로. LIST 권한 불필요(BR-8).
- **AWS Budgets $0.01 알림(FR-PIN-9o)**: 운영 안전망.
- **`next.config.ts` 검증**: `frontend/AGENTS.md` 경고대로 Next.js 16.2.6의 `experimental.serverActions.bodySizeLimit` 키를 `node_modules/next/dist/docs/`에서 확인 후 적용. 미상향 시 2MB 업로드가 Next 서버에서 거부됨.

---

## 확인 필요 사항

추가 확인 사항 없음. 설계 완료.
