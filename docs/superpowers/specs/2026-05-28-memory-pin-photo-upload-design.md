# 추억핀 사진 업로드 — 확정 설계 (v1)

> 최초 작성: 2026-05-28
> 상태: 확정 (구현 착수 가능)
> 범위: MEMORY(추억) 핀에 사진 1장 업로드 + 말풍선 썸네일 + 원본 뷰어
> 관련 도메인: [context/pin/](../../../context/pin/), [context/memo/architecture.md](../../../context/memo/architecture.md)

---

## 1. 배경 및 목표

현재 핀 상세(말풍선)는 텍스트 메모만 저장 가능하고 사진을 넣을 수 없다. 추억(MEMORY) 핀에 한정해 사진 1장을 붙일 수 있게 한다.

- 사용자 4명, 커플(그룹) 단위 비공개 서비스
- **S3 프리티어(5GB / GET 2만 / PUT·LIST 2천 / 전송 100GB) 안에서 사실상 무과금**으로 운영
- 지도·말풍선 부하 최소화: 평소엔 작은 썸네일만, 원본은 클릭 시에만

### 확정된 핵심 결정

| 항목 | 결정 |
|------|------|
| 사진 대상 | **MEMORY 핀에만**, 추억당 **1장** |
| 필수 여부 | **선택**(없어도 메모만 저장 가능) |
| 스토리지 | **신규 S3 버킷**(직접 생성, 가이드 포함) |
| 접근 방식 | **공개(public-read) + UUID 키** (추측 불가) |
| 캐싱 | `Cache-Control: public, max-age=31536000, immutable` |
| 업로드 파이프라인 | **프론트 압축(canvas) → 백엔드 검증 + 썸네일 생성 → S3 2객체 저장** |
| instagram 연계 | **무관**(순수 수동 업로드). `instagram_url`은 기존 발견 링크 용도 유지 |
| 렌디션 | **원본(장변 ~1600px)** + **썸네일(~256px, 프사용·blur-up 플레이스홀더)** 2종 |
| 말풍선 표시(1차) | 메모 **우측에 작은 원형 썸네일** → 클릭 시 **옆으로 창이 열려 원본** 보기 |
| 엽서 뒤집기 | **후속 옵션**(1차 범위 제외) |

---

## 2. 데이터 모델 — V013 마이그레이션

`pins` 테이블에 **nullable 컬럼 4개 추가**. 기존 핀 영향 없음, 백필 불필요.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `photo_key` | TEXT NULL | 원본 S3 객체 키 |
| `photo_thumbnail_key` | TEXT NULL | 썸네일 S3 객체 키 |
| `photo_uploaded_by` | BIGINT NULL | 업로드한 사용자 id |
| `photo_uploaded_at` | TIMESTAMPTZ NULL | 업로드 시각 |

- **URL이 아닌 S3 키를 저장** → 버킷/리전 교체 가능, 응답 DTO에서 공개 URL 조합
- 마이그레이션 파일: `V013__add_pins_photo.sql` (최신이 V012이므로 다음 번호)
- `Pin` 엔티티(`backend/.../domain/pin/Pin.java`):
  - 필드 4개 추가
  - `applyPhoto(photoKey, thumbnailKey, uploaderId)` / `clearPhoto()` 메서드 추가 (기존 `applyManualMemo`/`clearMemo` 패턴 답습)
- **사진은 MEMORY 핀에만 업로드** — 서비스 계층 검증(DB CHECK 미사용: tag 변경 유연성 보존)
- **tag가 MEMORY → 다른 값으로 바뀌어도 사진 레코드는 보존**(파괴적 삭제 회피). UI는 `tag == MEMORY`일 때만 표시. 다시 MEMORY로 돌아오면 재노출

---

## 3. 스토리지 계층 (S3)

### 3-1. 의존성 / 설정

- 신규 의존성: `software.amazon.awssdk:s3` (AWS SDK v2). 현재 백엔드에 AWS SDK 전무 → 신규 추가
- 설정값(기존 `.env` / SSM Parameter Store 패턴 사용):
  - `wherewego.s3.bucket`, `wherewego.s3.region`, `wherewego.s3.public-base-url`
- 자격증명:
  - **운영: EC2 IAM Role**(정적 키 미보관)
  - 로컬: `.env` 또는 기본 자격증명 체인(AWS CLI 프로필)

### 3-2. 키 스킴

```
pins/{groupId}/{pinId}/{uuid}.jpg          # 원본
pins/{groupId}/{pinId}/{uuid}_thumb.webp   # 썸네일(WebP)
```

- UUID로 추측 불가 → 공개 버킷이라도 URL을 모르면 접근 불가
- DB에는 키만 저장, 공개 URL = `{public-base-url}/{key}`

### 3-3. 버킷 프로비저닝 (가이드로 문서화)

- 객체 public-read: 버킷 정책으로 `pins/*` 에 `s3:GetObject` 허용 + "퍼블릭 액세스 차단(버킷 정책)" 항목 해제
- 업로드 시 모든 객체에 `Cache-Control: public, max-age=31536000, immutable` 부여
- **LIST 미사용**(키를 DB에 보관) → 2천건 LIST 한도 무관

### 3-4. 업로드 서비스 `PinPhotoStorage`

- 검증된 이미지 bytes 입력 → 썸네일 생성(Thumbnailator 또는 ImageIO, WebP 인코딩) → 원본·썸네일 put → 키 2개 반환
- **원자성**: 부분 실패(원본 성공, 썸네일 실패 등) 시 이미 올린 객체 정리 후 실패 처리

---

## 4. 백엔드 API

기존 JSON `PATCH`는 그대로 두고 **사진 전용 멀티파트 엔드포인트** 분리.

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/v1/groups/{groupId}/pins/{pinId}/photo` | multipart(`file`). 검증→썸네일→S3→필드 set→갱신된 `PinSummaryResponse` 반환 |
| `DELETE` | `/api/v1/groups/{groupId}/pins/{pinId}/photo` | S3 객체 2개 삭제 + 필드 클리어 |

- **교체 = 재 POST**: 기존 객체 best-effort 삭제 후 신규 업로드
- 검증: ① 핀이 `MEMORY`인가 ② 이미지 타입(jpeg/png/webp) ③ 크기 ≤ 2MB(프론트 압축 후 여유) ④ 픽셀 상한
- `PinSummaryResponse`(`PinSummary.java` / `PinV1Dto.java`)에 추가:
  - `photoUrl` (nullable, 원본 공개 URL)
  - `photoThumbnailUrl` (nullable, 썸네일 공개 URL)
- 분리 사유: 멀티파트/바이너리는 JSON PATCH와 성질이 달라 분리하면 PATCH가 깨끗하게 유지됨. tag→MEMORY 전환은 기존 PATCH, 사진은 후속 호출

---

## 5. 프론트엔드

### 5-1. API / 전송 계층

- `lib/api/http.ts`: body가 `FormData`면 `Content-Type: application/json` 강제 안 하도록 분기(브라우저가 boundary 설정)
- `lib/api/pin.ts`: `uploadPinPhoto(groupId, pinId, file)`, `deletePinPhoto(groupId, pinId)` 추가
- `app/map/actions.ts`: `uploadPinPhotoAction`, `deletePinPhotoAction` 서버액션 추가 + `revalidatePath('/pins')`
- `lib/api/types.ts`: `PinSummaryResponse`에 `photoUrl?`, `photoThumbnailUrl?` 추가

### 5-2. 클라이언트 압축 유틸

- canvas로 장변 1600px·JPEG ~0.8 변환 + **EXIF 방향 보정**
- 권장: `browser-image-compression` 라이브러리(EXIF 정확성). 수제 canvas 구현도 가능하나 방향 보정 주의

### 5-3. 공용 업로더 컴포넌트 `PinPhotoUploader`

- 파일 input `accept="image/*"`(+ 모바일 카메라), 미리보기, 진행률, 삭제
- **3곳 재사용**:
  1. `VisitMemoSheet.tsx` (방문→추억 전환): 메모 아래 사진 필드. 핀이 이미 존재 → 바로 업로드
  2. `MemoTagPanelContent.tsx` (신규 등록): `tag === "MEMORY"`일 때 노출. 신규는 id가 없으므로 **핀 생성(POST) → 반환된 pinId로 사진 업로드** 2-step
  3. `PinPopup.tsx` (수정): 추가/교체/삭제

### 5-4. 말풍선 표시 (1차 확정 UX)

- `SpeechBubblePopup.tsx`(메모 영역 L122~137): 메모 **우측에 작은 원형 썸네일**(프사 스타일 ~44px) 추가
  - `loading="lazy"`, 썸네일 URL은 영구·고정이라 강한 캐싱
- 썸네일 클릭 → **옆으로 슬라이드되어 열리는 원본 뷰어 창**
  - **blur-up**: 캐시된 썸네일을 흐릿하게 깔고 원본 로드되면 또렷해짐(스피너 없음, 추가 비용 0)
  - 닫기: 바깥 탭 / X / 스와이프
- 사진 없는 추억핀: 썸네일 미표시(기존 메모 말풍선 그대로). 수정 모드에서 "사진 추가" 가능

> **후속(1차 범위 외)**: 엽서 뒤집기(앞=사진, 뒤=메모, 동일 footprint 제자리 플립)를 향후 옵션으로 검토.

---

## 6. 비용·부하 전략 (프리티어 안전)

| 레버 | 효과 |
|------|------|
| `immutable` 캐시 헤더 | 기기당 이미지 1회만 GET, 이후 브라우저 캐시(재열람 시 GET 0) |
| 말풍선에 썸네일만(원본은 클릭 시) | 평상시 GET·전송량 최소 |
| 썸네일 WebP(~256px) | 전송량 추가 절감 |
| 키를 DB 보관 → S3 LIST 0건 | 2천건 LIST 한도 무관 |
| 프론트 압축(원본 ~300KB 상한) | 저장량·전송량 최소 |

**4명 추정치**: 저장 ~0.15GB / 5GB · 월 PUT 100건 미만 / 2천 · GET은 immutable 캐시로 한도의 수% 미만 · 전송 누적 수백 MB / 월 100GB.

- 한도 초과 유일 위험 = 캐시 무력화 실수(예: presigned URL이 매번 바뀜) → **이미 배제**(공개+UUID 채택)
- **운영 안전망**: AWS Budgets에 $0.01 알림 설정, Billing Dashboard 주기 확인
- **미래 확장 옵션**: 사용자가 수백 명 규모가 되면 CloudFront(Always Free 1TB·1천만 req) 앞단 도입 검토. 현재 4명 규모엔 불필요

---

## 7. 에러 처리

- 업로드 실패: 사진 필드 불변 + 에러 반환, 프론트 토스트/재시도. **메모 저장과 사진 업로드는 독립**(한쪽 실패가 다른 쪽을 막지 않음)
- S3 부분 실패: 업로드분 정리 후 원자적 실패
- 교체/삭제 시 옛 객체 삭제는 best-effort(실패 시 로그, 고아 객체는 무료한도상 무해)

---

## 8. 테스트

- 백엔드:
  - `PinPhotoStorage` 단위테스트(썸네일 생성·키 스킴)
  - 컨트롤러 테스트: `MockMultipartFile` + S3 클라이언트 목
  - 검증 실패 케이스(비-MEMORY 거부, 초과 크기 거부, 비이미지 거부)
- 프론트:
  - 압축 유틸(장변 상한·방향 보정)
  - `PinPhotoUploader` 컴포넌트, 썸네일 클릭 시 원본 뷰어 오픈

---

## 9. 롤아웃

1. V013 마이그레이션(nullable 컬럼) — 안전, 기존 핀 무영향
2. S3 버킷·IAM(EC2 Role)·버킷 정책 프로비저닝 (가이드 문서)
3. 백엔드 배포 → 프론트 배포
4. AWS Budgets $0.01 알림 설정

---

## 10. 영향받는 파일 (요약)

**백엔드**
- `db/migration/V013__add_pins_photo.sql` (신규)
- `domain/pin/Pin.java` (필드·메서드 추가)
- `domain/pin/PinService.java` (사진 업로드/삭제 유스케이스)
- `domain/pin/PinPhotoStorage.java` + S3 설정 (신규)
- `interfaces/api/pin/PinV1Controller.java` (멀티파트 엔드포인트 2개)
- `interfaces/api/pin/PinV1Dto.java`, `domain/pin/PinSummary.java` (photoUrl·photoThumbnailUrl)
- `build.gradle.kts` (AWS SDK v2 의존성)

**프론트엔드**
- `lib/api/http.ts` (FormData 분기), `lib/api/pin.ts`, `lib/api/types.ts`
- `app/map/actions.ts` (서버액션 2개)
- `app/map/_components/PinPhotoUploader.tsx` (신규)
- `app/map/_components/PinPhotoViewer.tsx` (신규, 옆으로 열리는 원본 뷰어)
- `app/map/_components/VisitMemoSheet.tsx`, `MemoTagPanelContent.tsx`, `PinPopup.tsx` (업로더 연결)
- `components/ui/SpeechBubblePopup.tsx` (썸네일 표시)
- 압축 유틸 + `browser-image-compression` 의존성

**문서**
- S3 버킷 프로비저닝 가이드(인프라 문서에 추가)
