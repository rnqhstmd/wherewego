# Phase 13 — 추억핀 사진 업로드

- 작성일: 2026-05-28
- 수정일: 2026-05-28
- 관련 레포: rnqhstmd/wherewego
- 상태: ✅ 완료 (2026-05-29, [#77](https://github.com/rnqhstmd/wherewego/pull/77))
- 설계 스펙: [docs/superpowers/specs/2026-05-28-memory-pin-photo-upload-design.md](../../docs/superpowers/specs/2026-05-28-memory-pin-photo-upload-design.md)

## 개요

MEMORY(추억) 핀에 한정해 사진 **1장**을 붙일 수 있게 한다. 추억 등록·방문 전환·수정 시점에 사진 업로드 폼이 노출되고, 말풍선에는 메모 우측에 작은 원형 썸네일(프사용)이 표시되며 클릭하면 옆으로 창이 열려 원본을 본다.

사용자 4명·커플 그룹 규모에서 **S3 프리티어(5GB / GET 2만 / PUT·LIST 2천 / 전송 100GB) 안에서 사실상 무과금** 운영을 전제로 한다.

> 사진은 MEMORY 핀 전용. REEL/WISH 핀에는 업로드 UI를 노출하지 않는다. instagram 미리보기 이미지와는 무관한 순수 수동 업로드.

---

## 확정 결정

| 항목 | 결정 |
|------|------|
| 대상 / 장수 | MEMORY 핀에만, 추억당 1장 |
| 필수 여부 | 선택 (사진 없이 메모만 저장 가능) |
| 스토리지 | 신규 S3 버킷 직접 생성 (가이드 포함) |
| 접근 방식 | 공개(public-read) + UUID 키 (추측 불가) |
| 캐싱 | `Cache-Control: public, max-age=31536000, immutable` |
| 업로드 파이프라인 | 프론트 압축(canvas, 장변 1600px) → 백엔드 검증 + 썸네일 생성 → S3 2객체 저장 |
| 렌디션 | 원본(장변 ~1600px JPEG, ~300KB) + 썸네일(~256px WebP, blur-up 플레이스홀더) |
| instagram 연계 | 무관 (순수 수동 업로드, `instagram_url`은 기존 발견 링크 용도 유지) |
| 말풍선 표시(1차) | 메모 우측 작은 원형 썸네일 → 클릭 시 옆으로 열리는 원본 뷰어(blur-up) |
| 태그 이탈 시 | MEMORY → 다른 태그로 바뀌어도 사진 레코드 보존, UI는 MEMORY일 때만 표시 |
| 엽서 뒤집기 | 후속 옵션 (1차 범위 제외) |

---

## 데이터 모델 — V013 마이그레이션

`pins` 테이블에 nullable 컬럼 4개 추가. 기존 핀 무영향, 백필 불필요.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `photo_key` | TEXT NULL | 원본 S3 객체 키 |
| `photo_thumbnail_key` | TEXT NULL | 썸네일 S3 객체 키 |
| `photo_uploaded_by` | BIGINT NULL | 업로드한 사용자 id |
| `photo_uploaded_at` | TIMESTAMPTZ NULL | 업로드 시각 |

- DB에는 **S3 키만 저장**, 공개 URL = `{public-base-url}/{key}` 로 응답 시 조합 (버킷/리전 교체 용이)
- 마이그레이션 파일: `V013__add_pins_photo.sql` (최신이 V012이므로 다음 번호, V006/V012 단일 합본 선례)
- `Pin` 엔티티: `applyPhoto(photoKey, thumbnailKey, uploaderId)` / `clearPhoto()` 메서드 추가 (기존 `applyManualMemo`/`clearMemo` 패턴 답습)
- MEMORY 한정·태그 이탈 보존은 **서비스 계층 검증** (DB CHECK 미사용, 태그 변경 유연성 보존)

---

## S3 스토리지

### 키 스킴
```
pins/{groupId}/{pinId}/{uuid}.jpg          # 원본
pins/{groupId}/{pinId}/{uuid}_thumb.webp   # 썸네일(WebP)
```
UUID로 추측 불가 → 공개 버킷이라도 URL을 모르면 접근 불가.

### 설정 / 자격증명
- 신규 의존성: `software.amazon.awssdk:s3` (AWS SDK v2, 현재 백엔드 전무)
- 설정값: `wherewego.s3.bucket` / `region` / `public-base-url` (기존 `.env` + SSM Parameter Store 패턴)
- 운영: **EC2 IAM Role** (정적 키 미보관) / 로컬: `.env` 또는 기본 자격증명 체인

### 버킷 프로비저닝 (가이드 문서화 필요)
- 버킷 정책으로 `pins/*` 에 `s3:GetObject` 공개 허용 + "퍼블릭 액세스 차단(정책)" 해제
- 업로드 시 모든 객체에 `Cache-Control: public, max-age=31536000, immutable` 부여
- **LIST 미사용**(키를 DB 보관) → 2천건 LIST 한도 무관

---

## 백엔드 API

기존 JSON `PATCH`는 유지, **사진 전용 멀티파트 엔드포인트** 분리.

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/v1/groups/{groupId}/pins/{pinId}/photo` | multipart(`file`). 검증→썸네일→S3→필드 set→갱신 `PinSummaryResponse` |
| `DELETE` | `/api/v1/groups/{groupId}/pins/{pinId}/photo` | S3 객체 2개 삭제 + 필드 클리어 |

- 교체 = 재 POST (기존 객체 best-effort 삭제 후 신규 업로드)
- 검증: ① 핀이 MEMORY ② 이미지 타입(jpeg/png/webp) ③ 크기 ≤ 2MB(프론트 압축 후 여유) ④ 픽셀 상한
- `PinSummaryResponse` / `PinSummary`에 `photoUrl`, `photoThumbnailUrl`(nullable) 추가
- 분리 사유: 멀티파트/바이너리는 JSON PATCH와 성질이 달라 PATCH를 깨끗하게 유지. 태그→MEMORY 전환은 기존 PATCH, 사진은 후속 호출

---

## 프론트엔드

- **전송 계층**(`lib/api/http.ts`): body가 FormData면 `Content-Type: application/json` 강제 안 함. `lib/api/pin.ts`에 `uploadPinPhoto`/`deletePinPhoto`, `actions.ts`에 `uploadPinPhotoAction`/`deletePinPhotoAction` 추가. `types.ts` `PinSummaryResponse`에 `photoUrl?`/`photoThumbnailUrl?`
- **클라이언트 압축**: canvas 장변 1600px·JPEG ~0.8 + EXIF 방향 보정 (`browser-image-compression` 권장)
- **공용 업로더 `PinPhotoUploader`**: 파일 input `accept=image/*`(+카메라), 미리보기, 진행률, 삭제 — 3곳 재사용:
  1. `VisitMemoSheet.tsx` (방문→추억 전환): 핀 존재 → 바로 업로드
  2. `MemoTagPanelContent.tsx` (신규 등록): `tag=MEMORY`일 때 노출. 신규는 id 없음 → **핀 생성(POST) → 반환 pinId로 업로드** 2-step
  3. `PinPopup.tsx` (수정): 추가/교체/삭제
- **말풍선 표시(1차)**: `SpeechBubblePopup.tsx` 메모 우측 원형 썸네일(~44px, `loading=lazy`) → 클릭 시 옆으로 열리는 `PinPhotoViewer`(신규)에서 원본. **blur-up**(캐시 썸네일 placeholder → 원본 선명화, 추가 비용 0). 사진 없으면 썸네일 미표시

---

## 비용·부하 전략 (프리티어 안전)

| 레버 | 효과 |
|------|------|
| `immutable` 캐시 헤더 | 기기당 이미지 1회만 GET, 재열람 시 GET 0 |
| 말풍선 썸네일만(원본은 클릭 시) | 평상시 GET·전송량 최소 |
| 썸네일 WebP(~256px) | 전송량 추가 절감 |
| 키 DB 보관 → S3 LIST 0건 | 2천건 LIST 무관 |
| 프론트 압축(원본 ~300KB 상한) | 저장량·전송량 최소 |

**4명 추정**: 저장 ~0.15GB/5GB · 월 PUT 100건 미만/2천 · GET은 immutable 캐시로 한도 수% 미만 · 전송 누적 수백 MB/100GB. 유일 위험 = 캐시 무력화(presigned 등) → 공개+UUID로 배제. 운영 안전망: **AWS Budgets $0.01 알림**. 미래 확장: 수백 명 시 CloudFront(Always Free) 검토.

---

## 에러 처리

- 업로드 실패: 사진 필드 불변 + 에러, 프론트 토스트/재시도. **메모 저장과 사진 업로드는 독립**
- S3 부분 실패: 업로드분 정리 후 원자적 실패
- 교체/삭제 시 옛 객체 삭제 best-effort (실패 시 로그, 고아 객체는 무료한도상 무해)

---

## 후속 (1차 범위 외)

- **엽서 뒤집기 뷰어**: 말풍선을 고정 footprint 카드로 두고 앞=사진 / 뒤=메모 제자리 3D 플립(엽서는 앞뒤 동일 크기라 리사이즈 없음). 1차의 "썸네일→옆 원본 뷰어"가 안정화된 뒤 검토.
