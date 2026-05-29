# Cross-Review 결과

- advisor: claude (qa-manager + security-auditor, cross-review 미션)
- 브랜치: feat/phase-13-memory-pin-photo (base: feat/phase-12-pin-experience-v2)
- DEV_DIR: .dev/feat-phase-13-memory-pin-photo
- 실행: 2026-05-29

## AC 충족 매트릭스

AC-1 ~ AC-17 **전항목 충족(O)**. 근거 요약:
- AC-1/6/7/8 S3 store/Cache-Control/키 스킴/원자성 — `S3PinPhotoStorage`
- AC-2 V013 nullable 4컬럼, AC-3 MEMORY 검증(`PinService:327`), AC-4 2MB(컨트롤러+advice), AC-5 타입(Content-Type+매직바이트)
- AC-9/10 삭제·교체, AC-13 태그 이탈 보존, AC-14 2-step, AC-15 활성멤버 403
- AC-11/12 말풍선 썸네일·blur-up 뷰어, AC-16 클라 압축, AC-17 FormData 분기

**[Must] 17/17 충족.**

## 설계 범위 이탈

**이탈 없음.** 범위 외 수정(CleanupService `from` 5-arg 적응, test/application.yml·EnvBindingTest S3 설정, docs 가이드, package-lock, context 문서)은 전부 설계 의도 또는 기술적 필연에 의한 정당한 수정.

## PRD 정책/설계 보안 약속 정합성 (security-auditor)

- BR-1(MEMORY 전용)·BR-2·BR-3(태그 이탈 보존)·BR-4(활성 멤버)·BR-5(원자성)·BR-6(메모/사진 독립)·BR-7(UUID)·BR-8(LIST 미사용)·NFR-6(서버만 업로드) — **전항목 정합**.
- 설계 보안 약속(S3 타임아웃 5s/3s, 매직바이트, 픽셀상한 4096, 원자성, 소프트삭제 정리, publicBaseUrl 정규화, MaxUploadSize 핸들러) — **전항목 정합**.
- trust-ledger 조치 4건(소프트삭제 S3 정리·매직바이트·트레일링 슬래시 정규화·pendingPhoto 리셋) — **모두 코드 반영 확인**.

## 신규 위험 (trust-ledger·self-check에 없는 것만)

### MEDIUM
- [GAP] `PinService.deletePhoto:356-360` — 사진 없는 핀(photoKey=null)에도 `deleteQuietly(null,null)` 호출 후 성공 응답. null skip이라 안전하나 호출 시맨틱 불명확. 권고: early-return 또는 멱등 성공 주석.
- [ASSUMPTION] `compressImage.ts:19` `fileType:"image/jpeg"` 강제가 Server Action(FormData) → `apiFetchServer` → 백엔드 경유 시 multipart Content-Type으로 유지되는지 미검증. null이면 `PIN_PHOTO_TYPE_INVALID` 거부. 권고: **배포 전 실제 Server Action 업로드 1회 통합 테스트로 Content-Type 수신값 확인**(또는 백엔드 JPEG 강제).

### Warning
- `MapClient.tsx:1445` — 사진 업로드/삭제 성공 시 `pinsCacheRef.fetchedAt` 갱신. 다른 핸들러와 캐시 갱신 정책 불일치 가능(4인 규모 실영향 낮음). 권고: 정책 통일.

### Info / LOW
- `PinPopup.tsx:525` — `viewerOpen && photoUrl && photoThumbnailUrl` 조건. photoThumbnailUrl만 있고 photoUrl 없는 비정상 상태(정상 운영 발생 불가)에선 뷰어 미오픈. 방어 코드 관찰.
- `S3PinPhotoStorage:75-77` — 원본 put 실패 `SdkException` catch에 `log.warn` 없음(썸네일 경로 deleteQuietly엔 있음). 운영 장애 추적 시 SDK 메시지 유실. 권고: 로깅 추가.

## 총평
- AC 17/17 충족, 설계 범위 이탈 0, PRD/보안 약속 전항목 정합, trust-ledger 조치 4건 코드 반영 확인.
- 신규 위험: MEDIUM 2 · Warning 1 · Info/LOW 2. **Critical/HIGH 0건, 배포 차단 수준 없음.**
- 권고: compressImage Content-Type 전달은 배포 전 통합 테스트 1회 권장. 나머지는 관측성/시맨틱 개선(선택).

## 처리 결과
- ✅ #2 [MEDIUM] `PinService.deletePhoto` — 사진 없는 핀(`!hasPhoto()`) early-return(멱등 성공, 불필요 S3 호출 제거). 수정됨.
- ✅ #5 [LOW] `S3PinPhotoStorage` — 원본/썸네일 put 실패 `SdkException`에 `log.warn` 추가(운영 추적). 수정됨.
- 🟡 #1 [MEDIUM] compressImage Content-Type Server Action 전달 — 배포 전 통합 테스트로 검증(PR 체크리스트 포함). 코드 수정 보류.
- 🟡 #3 [Warning] MapClient fetchedAt 캐시 일관성 — 수용(4인 규모 실영향 낮음).
- 🟡 #4 [Info] PinPopup 뷰어 조건 방어코드 — 수용(정상 운영 발생 불가).
- 컴파일 검증: `./gradlew :apps:wherewego-api:compileJava` BUILD SUCCESSFUL.
