# PRD: Phase 13 — 추억핀 사진 업로드

## 배경

현재 핀 상세(말풍선)는 텍스트 메모만 저장 가능하며, MEMORY(추억) 핀이라도 사진을 첨부할 수 없다. 커플 단위 4인 서비스에서 추억을 더 풍부하게 기록하려면 사진이 필요하다.

- FR-PIN-9/10/11이 이번 Phase의 구현 대상이며, 현재 상태는 모두 미구현(⬜)
- 최신 Flyway 마이그레이션은 V012(Phase 12 완료)이므로 다음 번호는 V013
- 백엔드에 AWS SDK가 전혀 없으므로 신규 추가 필요
- MEMORY → 다른 태그로 이탈해도 사진 레코드를 보존하는 정책이 이미 확정되어 있음

---

## 목표

- MEMORY 핀에 사진 1장을 선택적으로 첨부할 수 있게 한다
- 말풍선에서 썸네일로 사진을 미리 볼 수 있고, 클릭하면 원본을 확인할 수 있다
- 4인 규모에서 AWS S3 프리티어(5GB / GET 2만 / PUT·LIST 2천 / 전송 100GB) 안에서 무과금으로 운영한다

### 비목표 (이번 Phase 제외)

- 엽서 뒤집기 뷰어 (앞=사진, 뒤=메모 3D 플립)
- 복수 장 업로드
- REEL/WISH 핀 사진 첨부
- S3 LIST 작업(키는 DB에 보관하므로 불필요)
- CloudFront 도입 (4인 규모에서 불필요)
- 고아 S3 객체 자동 스케줄러 정리
- presigned URL 방식(공개+UUID 방식으로 대체 확정)
- 핀 소프트 삭제 시 S3 연쇄 삭제 (설계에서 명시되지 않음, 고아 객체는 프리티어상 무해)

---

## 요구사항

### 기능 요구사항

**데이터 모델**

- [Must] FR-PIN-9a: `pins` 테이블에 nullable 컬럼 4개를 추가하는 V013 마이그레이션을 적용한다 — `photo_key` (TEXT NULL, 원본 S3 키), `photo_thumbnail_key` (TEXT NULL, 썸네일 S3 키), `photo_uploaded_by` (BIGINT NULL, 업로드한 사용자 id), `photo_uploaded_at` (TIMESTAMPTZ NULL, 업로드 시각). DB에는 S3 키만 저장하며 URL은 저장하지 않는다.

**사진 업로드 API**

- [Must] FR-PIN-9b: `POST /api/v1/groups/{groupId}/pins/{pinId}/photo` (multipart `file`) — 백엔드가 검증 → 썸네일 생성 → S3 2객체 저장 → 갱신된 `PinSummaryResponse` 반환 순서로 처리한다. 클라이언트는 썸네일을 생성하지 않는다.
- [Must] FR-PIN-9c: 업로드 검증은 4가지를 모두 통과해야 한다 — ① 핀 태그가 MEMORY일 것 ② 파일 타입이 JPEG/PNG/WebP 중 하나일 것 ③ 파일 크기 ≤ 2MB일 것 ④ 픽셀 상한을 초과하지 않을 것.
- [Must] FR-PIN-9d: 백엔드가 생성하는 썸네일은 장변 약 256px WebP 포맷이다. 원본은 장변 약 1600px JPEG 약 300KB로 프론트에서 압축하여 전송한다.
- [Must] FR-PIN-9e: S3 키 스킴 — 원본: `pins/{groupId}/{pinId}/{uuid}.jpg`, 썸네일: `pins/{groupId}/{pinId}/{uuid}_thumb.webp`. 모든 객체에 `Cache-Control: public, max-age=31536000, immutable` 헤더를 부여한다.
- [Must] FR-PIN-9f: S3 버킷은 공개(public-read) + UUID 키 방식으로 운영한다. 응답 URL은 `{public-base-url}/{key}` 조합으로 생성한다.

**사진 삭제·교체**

- [Must] FR-PIN-10a: `DELETE /api/v1/groups/{groupId}/pins/{pinId}/photo` — S3 원본·썸네일 2객체 삭제 + `photo_key` 등 4개 필드 초기화.
- [Must] FR-PIN-10b: 교체는 재 POST로 처리한다. 기존 S3 객체는 best-effort로 삭제한다(실패 시 로그, 고아 객체는 무료 한도상 무해).

**말풍선 썸네일 및 원본 뷰어**

- [Must] FR-PIN-11a: 말풍선(`SpeechBubblePopup`) 메모 우측에 원형 썸네일(약 44px, `loading="lazy"`)을 표시한다. 사진이 없는 MEMORY 핀에서는 썸네일을 미표시한다.
- [Must] FR-PIN-11b: 썸네일 클릭 시 옆으로 슬라이드되어 열리는 `PinPhotoViewer` 원본 뷰어를 표시한다. blur-up 방식(캐시된 썸네일을 흐릿한 플레이스홀더로 깔고, 원본 로드 완료 시 선명하게 전환)을 적용한다. 스피너는 사용하지 않는다. 닫기는 바깥 탭 / X버튼 / 스와이프로 가능하다.

**공용 업로더 컴포넌트**

- [Must] FR-PIN-9g: 공용 `PinPhotoUploader` 컴포넌트를 만들어 3곳에서 재사용한다 — 파일 input `accept="image/*"` (모바일 카메라 포함), 미리보기, 진행률, 삭제 기능 포함.
- [Must] FR-PIN-9h: `VisitMemoSheet`(방문→추억 전환): 핀이 이미 존재하므로 업로더가 바로 업로드 API를 호출한다.
- [Must] FR-PIN-9i: `MemoTagPanelContent`(신규 등록): `tag === "MEMORY"`일 때 업로더를 노출한다. 신규 등록은 pinId가 없으므로 **핀 생성(POST) 성공 → 반환된 pinId로 사진 업로드** 2-step으로 처리한다.
- [Must] FR-PIN-9j: `PinPopup`(수정): 사진 추가/교체/삭제를 지원한다.

**클라이언트 압축**

- [Must] FR-PIN-9k: 클라이언트에서 canvas로 장변 1600px·JPEG 품질 약 0.8 압축 + EXIF 방향 보정을 수행한 뒤 전송한다. `browser-image-compression` 라이브러리 사용을 권장한다.

**응답 타입 변경**

- [Must] FR-PIN-9l: `PinSummaryResponse` / `PinSummary`에 `photoUrl` (nullable, 원본 공개 URL), `photoThumbnailUrl` (nullable, 썸네일 공개 URL) 필드를 추가한다. 이 값은 DB 컬럼이 아니라 키로부터 조합된 URL이다.

**인프라 설정**

- [Must] FR-PIN-9m: 백엔드에 `software.amazon.awssdk:s3` (AWS SDK v2) 의존성을 추가한다. 설정값 `wherewego.s3.bucket`, `wherewego.s3.region`, `wherewego.s3.public-base-url`을 적용한다. 운영 환경은 EC2 IAM Role(정적 키 미보관), 로컬은 `.env` 또는 기본 자격증명 체인을 사용한다.
- [Should] FR-PIN-9n: S3 버킷 프로비저닝 절차(버킷 정책, 퍼블릭 액세스 설정, IAM Role 설정 등)를 문서화한다.
- [Must] FR-PIN-9o: 운영 안전망으로 AWS Budgets에 $0.01 알림을 설정한다.

### 비즈니스 규칙

- [Must] BR-1: 사진은 MEMORY 핀에만 업로드할 수 있다. REEL/WISH 핀에 대한 업로드 요청은 거부한다. 이 검증은 서비스 계층에서 수행한다(DB CHECK 제약 미사용 — 태그 변경 유연성 보존 목적).
- [Must] BR-2: MEMORY 핀에 사진은 선택 사항이다. 사진 없이 메모만 저장할 수 있다.
- [Must] BR-3: 태그가 MEMORY에서 다른 값으로 변경되어도 사진 레코드(DB 및 S3)를 삭제하지 않는다. UI는 태그가 MEMORY일 때만 사진을 표시한다. 태그가 다시 MEMORY로 돌아오면 사진이 재노출된다.
- [Must] BR-4: 업로드 권한은 활성 GroupMember 전체에게 부여한다. 등록자 본인만 가능하지 않다. 비활성 멤버는 403 GROUP_NOT_MEMBER.
- [Must] BR-5: S3 업로드의 원자성을 보장한다. 원본 업로드 성공 후 썸네일 업로드 실패 시, 이미 업로드된 원본을 정리하고 실패를 반환한다.
- [Must] BR-6: 메모 저장과 사진 업로드는 독립적으로 처리한다. 사진 업로드 실패가 메모 저장을 막지 않는다.
- [Must] BR-7: S3 키는 UUID 기반으로 생성하여 추측 불가능하게 한다. 공개 버킷이라도 URL을 모르면 접근할 수 없다.
- [Must] BR-8: S3에 LIST 요청을 보내지 않는다. 키를 DB에 보관함으로써 2천건 LIST 한도와 무관하게 운영한다.

### 품질 기대

- [Should] QE-1: 4인 규모에서 S3 프리티어(5GB / GET 2만 / PUT·LIST 2천 / 전송 100GB) 한도를 초과하지 않는다. 추정 저장량 약 0.15GB/5GB, 월 PUT 100건 미만/2천, GET은 immutable 캐시로 한도의 수% 미만.
- [Should] QE-2: 말풍선 지도 로딩 시 평상시에는 썸네일만 GET하고, 원본은 클릭 시에만 GET한다.
- [Should] QE-3: blur-up 전환 시 스피너를 노출하지 않는다. 추가 S3 GET 비용이 발생하지 않는다(캐시 썸네일을 플레이스홀더로 활용).

---

## 사용자 시나리오

### 정상 흐름

**시나리오 1 — 신규 추억 등록 시 사진 첨부 (2-step)**
1. 사용자가 지도에서 장소를 선택하고 태그를 MEMORY로 설정한다.
2. `MemoTagPanelContent`에 `PinPhotoUploader`가 표시된다.
3. 사용자가 사진을 선택하면 클라이언트가 장변 1600px·JPEG 0.8로 압축하고 EXIF 방향을 보정한다.
4. 등록 버튼을 누르면 ① 핀 생성(POST) → ② 반환된 pinId로 사진 업로드 순서로 처리된다.
5. 말풍선에 원형 썸네일이 표시된다.

**시나리오 2 — 방문 감지 후 추억 전환 시 사진 첨부**
1. WISH/REEL 핀 100m 이내 30초 머무름으로 방문이 감지되어 태그가 MEMORY로 전환된다.
2. `VisitMemoSheet`가 열리고 메모 아래 `PinPhotoUploader`가 표시된다.
3. 사용자가 사진을 선택하고 저장하면 pinId가 이미 존재하므로 바로 업로드 API를 호출한다.

**시나리오 3 — 기존 추억핀 수정 시 사진 교체**
1. 사용자가 `PinPopup`에서 기존 사진을 교체한다.
2. 기존 S3 객체가 best-effort로 삭제된 후 신규 사진이 업로드된다.
3. `PinSummaryResponse`의 `photoUrl`/`photoThumbnailUrl`이 새 URL로 갱신된다.

**시나리오 4 — 말풍선에서 원본 사진 확인**
1. 사용자가 지도에서 MEMORY 핀 말풍선을 연다.
2. 메모 우측에 원형 썸네일(~44px)이 표시된다.
3. 썸네일을 클릭하면 `PinPhotoViewer`가 옆으로 슬라이드되어 열린다.
4. 캐시된 썸네일이 흐릿하게 먼저 표시되고, 원본 로드 완료 시 선명하게 전환된다.

### 예외 흐름

- **비-MEMORY 핀에 업로드 시도**: 백엔드가 MEMORY 검증 실패로 400/422를 반환한다. 프론트는 토스트로 안내한다.
- **파일 크기 초과(2MB)**: 백엔드 검증 실패. 프론트가 토스트로 안내하고 재시도를 유도한다.
- **지원하지 않는 파일 타입**: 백엔드 검증 실패. 프론트 토스트 안내.
- **업로드 중 네트워크 실패**: 사진 필드 불변 상태 유지. 메모는 이미 저장된 경우 영향 없음. 프론트 토스트로 재시도 유도.
- **S3 부분 실패**: 원본 업로드 성공 후 썸네일 생성/업로드 실패 시, 원본을 정리하고 실패 응답을 반환한다. DB 필드는 변경되지 않는다.
- **사진 없는 MEMORY 핀**: 말풍선에 썸네일이 미표시된다. 기존 텍스트 메모 말풍선 UI 그대로. 수정 모드에서 "사진 추가" 가능.
- **2-step 중 사진 업로드 실패**: 핀은 이미 생성되어 있으므로 사진 없는 상태로 저장된다. 사용자는 나중에 수정 모드(`PinPopup`)에서 사진을 추가할 수 있다.
- **태그 MEMORY → 다른 태그 변경 후 재변경**: MEMORY로 돌아오면 기존 사진이 그대로 재노출된다(S3/DB 삭제 없음).
- **교체 중 기존 객체 삭제 실패**: best-effort로 처리. 로그만 기록하고 신규 업로드는 성공으로 처리한다.

---

## 영향 범위

### 영향받는 기존 기능

| 영역 | 변경 내용 |
|------|-----------|
| `PinSummaryResponse` / `PinSummary` | `photoUrl`, `photoThumbnailUrl` nullable 필드 추가 — 기존 클라이언트는 null로 수신하여 하위 호환 유지 |
| `SpeechBubblePopup` | 메모 우측에 썸네일 영역 추가 — 사진 없는 경우 레이아웃 변화 없음 |
| `MemoTagPanelContent` | `tag=MEMORY`일 때 업로더 영역 추가 |
| `VisitMemoSheet` | 메모 아래 업로더 영역 추가 |
| `PinPopup` | 사진 관리(추가/교체/삭제) 기능 추가 |
| `lib/api/http.ts` | FormData body일 때 `Content-Type: application/json` 강제 안 함 |
| `app/map/actions.ts` | 서버 액션 2개 추가 + `revalidatePath` |

### 기존 사용자 영향

- 기존 MEMORY 핀은 photo 필드가 null로 유지되며, 말풍선에 변화 없음
- 기존 REEL/WISH 핀은 업로드 UI가 노출되지 않으므로 영향 없음
- V013 마이그레이션은 nullable 컬럼 추가이므로 기존 핀 데이터에 영향 없음

---

## 수용 기준

| # | 수용 기준 | 매핑 |
|---|-----------|------|
| AC-1 | Given MEMORY 핀이 존재할 때, When 활성 멤버가 JPEG/PNG/WebP 타입 2MB 이하 파일을 `POST .../photo`로 업로드하면, Then S3에 원본(`.jpg`)과 썸네일(`.webp`) 2객체가 저장되고, `PinSummaryResponse`에 `photoUrl`과 `photoThumbnailUrl`이 non-null로 반환된다 | FR-PIN-9b, FR-PIN-9d, FR-PIN-9l |
| AC-2 | Given `pins` 테이블에 V013 마이그레이션이 적용되면, Then `photo_key`, `photo_thumbnail_key`, `photo_uploaded_by`, `photo_uploaded_at` 4개 컬럼이 추가되고, 기존 핀 레코드는 4개 필드가 null인 채로 존재한다 | FR-PIN-9a |
| AC-3 | Given MEMORY가 아닌 핀(REEL/WISH)에 대해 업로드 요청이 오면, Then 백엔드가 검증 실패 에러를 반환하고 S3에 객체가 저장되지 않는다 | BR-1, FR-PIN-9c |
| AC-4 | Given 2MB를 초과하는 파일을 업로드 시도하면, Then 백엔드 검증 실패 에러가 반환되고 사진 필드는 변경되지 않는다 | FR-PIN-9c |
| AC-5 | Given JPEG/PNG/WebP가 아닌 파일(예: GIF, PDF)을 업로드 시도하면, Then 백엔드 검증 실패 에러가 반환된다 | FR-PIN-9c |
| AC-6 | Given S3에 저장된 모든 사진 객체는, Then `Cache-Control: public, max-age=31536000, immutable` 헤더를 가진다 | FR-PIN-9e, QE-2 |
| AC-7 | Given 업로드된 원본의 S3 키가 `pins/{groupId}/{pinId}/{uuid}.jpg` 형식이고 썸네일 키가 `pins/{groupId}/{pinId}/{uuid}_thumb.webp` 형식이면, Then `photoUrl` = `{public-base-url}/pins/{groupId}/{pinId}/{uuid}.jpg`, `photoThumbnailUrl` = `{public-base-url}/pins/{groupId}/{pinId}/{uuid}_thumb.webp` 로 조합되어 반환된다 | FR-PIN-9e, FR-PIN-9f, FR-PIN-9l |
| AC-8 | Given 원본 업로드 성공 후 썸네일 업로드가 실패하면, Then 원본 S3 객체가 정리되고 핀의 photo 필드는 변경되지 않으며 에러 응답이 반환된다 | BR-5 |
| AC-9 | Given `DELETE .../photo` 요청 시, Then S3 원본·썸네일 2객체가 삭제되고 DB의 `photo_key`, `photo_thumbnail_key`, `photo_uploaded_by`, `photo_uploaded_at`이 null로 초기화된다 | FR-PIN-10a |
| AC-10 | Given 사진이 있는 MEMORY 핀에 재 POST로 교체 요청 시, Then 기존 S3 객체 삭제(best-effort) 후 신규 객체가 업로드되고 `PinSummaryResponse`의 URL이 새 값으로 갱신된다 | FR-PIN-10b |
| AC-11 | Given 사진이 있는 MEMORY 핀의 말풍선을 열면, Then 메모 우측에 약 44px 원형 썸네일이 표시된다. Given 사진이 없는 MEMORY 핀의 말풍선을 열면, Then 썸네일이 표시되지 않고 기존 말풍선 레이아웃이 유지된다 | FR-PIN-11a |
| AC-12 | Given 말풍선에서 썸네일을 클릭하면, Then `PinPhotoViewer`가 옆으로 슬라이드 오픈되고, 썸네일이 흐릿한 플레이스홀더로 먼저 표시된 뒤 원본 로드 완료 시 선명하게 전환된다(blur-up). 스피너는 표시되지 않는다 | FR-PIN-11b, QE-3 |
| AC-13 | Given MEMORY 태그가 다른 태그로 변경되면, Then DB의 `photo_key` 등 사진 필드가 유지되고 S3 객체도 삭제되지 않는다. 말풍선에서 해당 핀은 사진 없는 비-MEMORY 핀으로 표시된다. 태그가 다시 MEMORY로 복귀하면 기존 사진이 재노출된다 | BR-3 |
| AC-14 | Given 신규 등록에서 tag=MEMORY 선택 시, When 등록 버튼을 누르면, Then 핀 생성 API 성공 후 반환된 pinId로 사진 업로드 API를 순서대로 호출한다. 사진 업로드가 실패해도 핀은 사진 없는 상태로 저장되며 사용자에게 안내 메시지가 표시된다 | FR-PIN-9i |
| AC-15 | Given 비활성 GroupMember가 업로드를 시도하면, Then 403 GROUP_NOT_MEMBER 응답이 반환된다 | BR-4 |
| AC-16 | Given 클라이언트에서 사진 파일을 선택하면, Then canvas 압축(장변 1600px, JPEG 품질 약 0.8, EXIF 방향 보정)이 적용된 파일이 서버로 전송된다 | FR-PIN-9k |
| AC-17 | Given `lib/api/http.ts`에서 body가 FormData인 경우, Then `Content-Type: application/json` 헤더를 강제로 설정하지 않아 브라우저가 boundary를 포함한 multipart/form-data를 자동 설정한다 | FR-PIN-9b |

---

## 엣지케이스 보완

| 케이스 | 처리 방식 |
|--------|-----------|
| 사진 없는 MEMORY 핀 | 말풍선 썸네일 미표시, 기존 레이아웃 유지. 수정 모드에서 추가 가능 |
| 2-step(신규 등록) 중 사진 업로드 실패 | 핀은 생성됨. 사진 없는 상태로 저장. 사용자에게 토스트 안내 + `PinPopup` 수정으로 재시도 가능 |
| S3 부분 실패(원본 성공 / 썸네일 실패) | 원본 객체 정리 후 원자적 실패 반환. DB 필드 불변 |
| 교체 중 기존 객체 삭제 실패 | best-effort. 로그 기록, 신규 업로드 성공 처리, 고아 객체는 프리티어 내 무해 |
| 태그 MEMORY 이탈 | S3/DB 삭제 없음, 사진 레코드 보존. UI에서만 비표시 |
| 비-MEMORY 핀 업로드 시도 | 백엔드 검증 실패, 프론트 UI에서도 REEL/WISH 핀에 업로더 미노출 |
| 파일 크기 2MB 초과 | 백엔드 검증 실패 반환. 사진 필드 불변 |
| 지원 타입 외 파일 | 백엔드 검증 실패 반환 |
| 동시 교체 요청 | PATCH/DELETE 패턴(`PESSIMISTIC_WRITE`) 비관 락 적용 |
| 업로드 중 네트워크 단절 | 사진 필드 불변. 프론트 토스트 재시도 안내 |

---

## 확인 필요 사항

추가 확인 사항 없음. PRD 확정.
