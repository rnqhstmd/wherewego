# Trust Ledger — Phase 13 추억핀 사진 업로드

> phase-review 통합 감사 (qa-manager + security-auditor). 기계 게이트: backend build/next build/vitest 165 통과, 백엔드 테스트 22실패는 베이스 선행(무관) 확인.

## QA (qa-manager) — Critical 0건, AC 17/17 충족
- [Warning] `PinService.deletePhoto` — MEMORY 검증·사진 존재 가드 없음(멱등 성공). 의도 확인 필요.
- [Warning] `MapClient.handlePhotoUpload` — 실패 토스트로 `visitErrorMessage`(1.5초 자동닫힘) 재사용(Q4 확정 트레이드오프). UX 운영 후 재검토.
- [Info] `PinPhotoViewer.tsx:98` — transition 분기를 ref(`touchStartY.current`)로 판단 → `dragY>0` 권장.
- [Info] `EnvBindingTest` — S3 `@NotBlank` 누락 케이스 테스트 없음(Should).
- [QUESTION] deletePhoto MEMORY 검증 생략 의도 여부 / `groupId ?? groupId` 단순화 / handlePhotoUpload fetchedAt 갱신 필요성.

## 보안 (security-auditor) — CRITICAL 0
### HIGH
- [RISK] `PinV1Controller:176` — 파일 타입 검증이 `Content-Type` 헤더(클라 조작 가능)에만 의존. 보완선=어댑터 scrimage 디코딩(`S3PinPhotoStorage:141`)이나 인프라 구현에 암묵 의존. 권고: 매직바이트(JPEG FFD8FF/PNG 89504E47/WEBP RIFF…WEBP) 명시 검증.
- [RISK] `PinService.softDeletePin:296` — 핀 소프트 삭제 시 S3 객체 미삭제. 공개+immutable 버킷이라 **삭제된 사진이 URL로 영구 접근 가능**(프라이버시). PRD 비목표는 "고아=비용 무해"로만 기술, 보안 함의 미명시. 권고: 삭제 시 hasPhoto면 deleteQuietly best-effort.
- [ASSUMPTION] `S3Properties.publicBaseUrl` — `@NotBlank`만, https/도메인 형식 미검증. 권고: `@Pattern`/`@AssertTrue`.
### MEDIUM
- WebP는 ImageIO 헤더 reader 없어 차원 선확인 skip → scrimage full 디코딩 후 차원 재확인(`:144`)이 최후 방어선. 2MB 캡으로 위험 제한. 주석/테스트 권고.
- `deletePhoto` MEMORY 태그 검증 없음(BR-3 보존 핀의 사진을 활성 멤버가 삭제 가능, 그룹 신뢰 전제로 낮음).
- 크기 경계: 컨트롤러 2MB vs multipart 5MB → 2~5MB 구간 파일이 리소스 소비 후 거부. max-file-size 3MB 권고.
- `toPublicUrl` 트레일링 슬래시 미정규화 → publicBaseUrl 끝에 `/` 있으면 `//` 이중 슬래시 broken URL. 권고: stripTrailing.
- `compressPinPhoto` 보안 제어 아닌 편의 기능임 주석 명시 권고.
### LOW/관찰
- 삭제/캐시: immutable 캐시로 브라우저 잔존(CDN 미도입, PRD 인지). MaxUploadSize 전역 핸들러 향후 오탐 가능. 로컬 `.env` AWS 자격증명 노출(`.gitignore` 미확인). NON_NULL 직렬화로 photoUrl 키 누락(프론트 optional 처리됨).

## 조치 결과 (사용자 결정 반영)
- ✅ [HIGH] 소프트 삭제 S3 정리 추가 — `softDeletePin`에서 hasPhoto면 deleteQuietly best-effort. 삭제된 사진 영구 노출 차단.
- ✅ [HIGH] 매직바이트 2차 검증 추가 — `PinV1Controller`에서 JPEG/PNG/WebP 시그니처 검증(Content-Type 헤더 의존 보완).
- ✅ [MEDIUM] publicBaseUrl 트레일링 슬래시 정규화 — `toPublicUrl`에서 `replaceAll("/+$","")`.
- ✅ [Warning] MemoTagPanelContent 태그 변경 시 pendingPhoto 리셋.
- 🟡 수용(Ledger 기록만): deletePhoto MEMORY 게이트 생략(명시 삭제는 태그 무관 허용, BR-3 취지), max-file-size 5MB, WebP 폭탄(2MB 캡+사후 차원체크 backstop), publicBaseUrl @Pattern 미적용, compressImage 보안 아님, groupId??/fetchedAt(코스메틱), immutable 캐시 잔존, .env 노출(.gitignore에 .env 패턴 존재 확인됨), NON_NULL 직렬화(프론트 optional 처리).
- 확인 리뷰: 수정 4건 정확 적용 + 신규 결함 0건 (qa-manager).

## PRD 정합성 교차검증 (security-auditor)
BR-1/3/4/5/7/8, NFR-6, FR-PIN-9c/9e/9n, AC-17 모두 [정합]. [불일치] 소프트 삭제 후 사진 URL 영구 공개의 보안 함의가 PRD에 미명시.
