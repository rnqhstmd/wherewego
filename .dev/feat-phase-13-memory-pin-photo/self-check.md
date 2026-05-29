# 자기점검 결과 — Phase 13 추억핀 사진 업로드

> qa-manager 1회 패스. Critical CERTAIN 0건. AC 17/17 충족. 빌드(backend compileJava ✓ / frontend tsc ✓) 통과 전제.

## CERTAIN (자동 수정 대상)
- 없음 (Critical 0건).

## SELF_CHECK_FINDINGS (Warning/Info — phase-review 이월)
- [Warning] `frontend/src/app/map/_components/MemoTagPanelContent.tsx:117` — 태그 변경 후 `pendingPhoto` 미초기화. MEMORY 선택 → 사진 선택 → 다른 태그(WISH/REEL)로 변경 후 저장 시 비-MEMORY 핀에 사진 업로드 API 호출 → 백엔드 `PIN_PHOTO_NOT_MEMORY`(400)로 거절되어 데이터 오염은 없으나 불필요한 실패 토스트. 수정 방안: 태그가 MEMORY 이외로 바뀌면 `setPendingPhoto(null)`.
- [Info] `frontend/src/app/map/_components/PinPhotoUploader.tsx:46-51,74-76` — object URL을 useEffect cleanup과 setLocalPreview 콜백에서 이중 revoke(명세상 no-op). cleanup 일원화 권장.
- [Info] `backend/.../infrastructure/pin/S3PinPhotoStorage.java:80-97` — SdkException → PIN_PHOTO_STORAGE_FAILED 래핑 시 원인 예외 미로깅. `log.warn(...)` 추가 권장.

## SELF_CHECK_QUESTIONS (phase-review 이월)
- Q1 `PinService.java` uploadPhoto — `@Transactional` + PESSIMISTIC_WRITE 락 보유 중 S3 블로킹 I/O(최대 5초×2). 4인 MVP라 실영향 무시 가능. (a)현재 유지 권장 / (b)트랜잭션 밖 분리.
- Q2 `S3PinPhotoStorage.java:62` — 원본 S3 contentType을 요청 MIME 그대로 사용. compressImage가 `fileType:"image/jpeg"`로 강제하나 라이브러리 버전 의존. (a)유지 / (b)백엔드 JPEG 강제.
- Q3 `PinService.java` deletePhoto — `clearPhoto()`(DB) 후 `deleteQuietly()`(S3)가 커밋 전 호출. best-effort라 허용. (a)유지 / (b)AFTER_COMMIT 이벤트로 이동.
- Q4 `MapClient.tsx` — 사진 업로드/삭제 실패 토스트로 `visitErrorMessage` 재사용. 방문 흐름과 메시지 공유 오인 가능. (a)의도된 재사용 / (b)별도 photoErrorMessage state.
- Q5 `PinPhotoViewer.tsx:99` — swipe transition 분기를 ref(`touchStartY.current`)로 판단. (a)유지 / (b)isDragging state로 교체.

## AC 충족
AC-1~17 전부 충족 (qa-manager 확인). 명백한 스펙 위배·런타임 버그 미발견.
