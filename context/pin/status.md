# pin 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-PIN-1 | 핀 등록 (그룹 스코프, tag 필수) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — 챗봇 자동 등록 경로 (`PinService.registerFromInstagram/registerFromSelection`, tag=PLACE 고정) |
| FR-PIN-2 | 동일 group_id + instagram_url 중복 방지 (UNIQUE) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — DB `uq_pin_group_instagram` + `DataIntegrityViolationException` catch + `PLC_DUPLICATE_PIN` 응답 |
| FR-PIN-3 | 핀 목록 조회 (그룹별, tag 필터 옵션) | ✅ | [#9](https://github.com/rnqhstmd/wherewego/pull/9) — `PinService.listGroupPins` + `GET /api/v1/groups/{groupId}/pins?tag=` + Next.js `/pins` UI |
| FR-PIN-4 | 핀 수정 (memo, tag, placeName, address 변경 가능) | ✅ | [#9](https://github.com/rnqhstmd/wherewego/pull/9), [#21](https://github.com/rnqhstmd/wherewego/pull/21) — `PinService.updatePin` + `PATCH .../pins/{pinId}` (JsonNode 부분 수정, 빈 메모 잠금 해제, PESSIMISTIC_WRITE). Phase 2.8에서 `PinUpdateCommand` 4→8 필드 확장 + placeName(1~200자 필수)/address(≤500자, 빈 문자열은 미변경 정규화) 분기 추가 |
| FR-PIN-5 | 핀 삭제 (활성 GroupMember만) | ✅ | [#9](https://github.com/rnqhstmd/wherewego/pull/9) — `PinService.softDeletePin` + `DELETE .../pins/{pinId}` (204, BaseEntity.delete 멱등, 등록자 무관) |
| FR-PIN-6 | 핀 직접 등록 웹 API (검색 결과 또는 십자선 좌표 + tag 선택 + 메모) | ✅ | [#13](https://github.com/rnqhstmd/wherewego/pull/13) — `POST /api/v1/groups/{groupId}/pins` (`PinService.addPin` + `Pin.createFromUser` + `PinCreateCommand`). `@Valid` Bean Validation + `toCommand()` 이중 검증, UNIQUE 충돌 → `PLC_DUPLICATE_PIN` 변환, `requireActiveMembership` 권한 검증 |
| FR-PIN-7 | 핀 장소 좌표 수정 (백엔드 API) | ✅ | [#24](https://github.com/rnqhstmd/wherewego/pull/24) — `PATCH .../pins/{pinId}` 에 `latitude/longitude` BigDecimal 직접 필드 + `PinUpdateCommand.coordinateProvided` 단일 플래그, 범위 검증, 한쪽만 전달 시 `PIN_COORDINATE_INVALID`(400) |
| FR-PIN-8 | 핀 장소 좌표 수정 (지도 picker UX) | ✅ | [#24](https://github.com/rnqhstmd/wherewego/pull/24) — `PinPopup` ⋮ "좌표 수정" 진입점 → `PinCoordinateEditPicker` 시트 → `useOptimistic patch` 즉시 갱신 + 실패 시 자동 롤백 + 인라인 에러 |
| FR-PIN-9 | 추억핀 사진 업로드 (MEMORY 한정, 1장 선택, 멀티파트) | ✅ | [#77](https://github.com/rnqhstmd/wherewego/pull/77) — `POST .../pins/{pinId}/photo` (`PinV1Controller` Content-Type+매직바이트·크기(2MB)·픽셀(4096) 검증 → `PinService.uploadPhoto` MEMORY 검증 → `S3PinPhotoStorage` WebP 썸네일(256px)+S3 2객체, 원자성·교체 시 기존 키 정리). V013 nullable 컬럼 4개, `PinSummaryResponse.photoUrl/photoThumbnailUrl` 조합 |
| FR-PIN-10 | 추억핀 사진 삭제/교체 | ✅ | [#77](https://github.com/rnqhstmd/wherewego/pull/77) — `DELETE .../pins/{pinId}/photo` (`PinService.deletePhoto` → `Pin.clearPhoto` 4필드 null + S3 deleteQuietly). 교체=재 POST(옛 객체 best-effort 삭제). 핀 소프트 삭제 시 S3 연쇄 정리 |
| FR-PIN-11 | 말풍선 썸네일 + 원본 뷰어 | ✅ | [#77](https://github.com/rnqhstmd/wherewego/pull/77) — `SpeechBubblePopup` 메모 우측 원형 썸네일(44px lazy) → `PinPhotoViewer` 슬라이드+blur-up(스피너 없음). 공용 `PinPhotoUploader` 3곳 재사용(신규등록 2-step·방문전환·수정), 클라 압축(1600px JPEG) |
| ~~FR-PIN-X~~ | ~~방문 인증 토글~~ — **제거됨** | — | |

## 후속 작업

- **방문 정책 v2 구현 완료 (2026-06-12)**: 체크인·추억 전환 — Phase 10의 "1인 확인→즉시 MEMORY 전환+VISIT_DETECTED 알림"을 대체. 혼자=체크인(태그 보존, `pin_visits` SELF upsert)/둘 이상 동행 선언=MEMORY 전환(비관 락 안 멱등+union, TAGGED→SELF 승격, 1인 그룹 예외). 단일 API `POST .../pins/{pinId}/visits`(companionUserIds 빈=혼자). 그룹 공유=채팅 카드 2종(PIN_VISIT 무푸시·PIN_MEMORY 푸시+동행 아바타 visitParticipants, 방문자 명의 서버 적재) — **VISIT_DETECTED 완전 폐기**(V023이 과거 행 삭제, 생성·렌더·IT 제거). 핀 응답 `visitors[]` 합류(IN 배치+프사 resolver). iOS: VisitToast→VisitCompanionSheet·submitVisit 3분기·말풍선 방문자 아바타 스택·방문 카드 버블. ⚠️ 신규 MessageKind 2종은 서버·앱 동시 배포 전제. 정책: [visit-checkin-policy.md](visit-checkin-policy.md) — [#127](https://github.com/rnqhstmd/wherewego/pull/127)

- **Phase 13 완료 (2026-05-29)**: 추억핀 사진 업로드 — MEMORY 핀 한정 1장(선택). 신규 S3 버킷(공개+UUID 키, `Cache-Control: immutable`), 프론트 압축(장변 1600px JPEG)→백엔드 Content-Type+매직바이트·크기(2MB)·픽셀(4096) 검증+썸네일(256px WebP, scrimage) 생성→원본/썸네일 2객체 저장(원자성). 멀티파트 `POST/DELETE .../pins/{pinId}/photo`, `PinSummaryResponse`에 `photoUrl`/`photoThumbnailUrl`(키→URL 조합, NON_NULL), V013 nullable 컬럼 4개(`photo_key`/`photo_thumbnail_key`/`photo_uploaded_by`/`photo_uploaded_at`). 도메인 포트 `PinPhotoStorage`↔어댑터 `S3PinPhotoStorage`(AWS SDK v2 최초 도입). 태그 MEMORY 이탈 시 사진 보존·UI 비표시(`Pin`은 미변경), 핀 소프트 삭제 시 S3 best-effort 정리. 말풍선 메모 우측 원형 썸네일→`PinPhotoViewer` blur-up. 공용 `PinPhotoUploader` 3곳 재사용(방문 전환·신규 등록 2-step·수정). 4명 규모 프리티어 무과금. 설계: [phase-13-memory-pin-photo.md](phase-13-memory-pin-photo.md) / [스펙](../../docs/superpowers/specs/2026-05-28-memory-pin-photo-upload-design.md) — [#77](https://github.com/rnqhstmd/wherewego/pull/77)

- **Phase 12 완료**: Pin Experience v2 — `pin_events` 테이블(P0=WANT만, D-19 영구 멱등 UNIQUE) + `pins.want_count` 캐시 컬럼 + `WantService` 토글(FOR UPDATE) + 과반 자동 WISH 전환 + `WishConvertedEvent` AFTER_COMMIT → `NotificationType.WISH_CONVERTED` 인앱 알림(본인 제외, V009 `visit_pin_id` 답습 `wish_pin_id` + 부분 UNIQUE). 마커 3단계(하늘색→진보라 `#7B68EE` 1.1배→노랑 별 1.2배 + WISH 전환 0.5초 펄스, MapboxView pulsingPinId prop). 챗봇 v2 ReelSavedSelectionSession 상태머신(SINGLE_WANT/MULTI_SELECTING/BULK_SAVE 31개+/MEMO_WAITING, 3분 고정 TTL Caffeine Expiry, INSERT-only markWantOnInitialSave 헬퍼). 오래된 REEL 정리(`tag=REEL + AUTO + 30일+ + want_count=0`, 일괄 soft delete, DB `users.cleanup_snoozed_until` 7일 snooze 다기기 일관). PinPopup/PinCard 출처 뱃지(📹/✏️) + 태그 진행 다이어그램 모달 진입점. V012 단일 Flyway. 상세: [phase-12-pin-experience-v2.md](phase-12-pin-experience-v2.md) — [#76](https://github.com/rnqhstmd/wherewego/pull/76)


- **메모 수정자 추적 완료 (2026-05-23)**: `pins.memo_updated_by BIGINT NULL` 컬럼 추가 (V008 Flyway). `Pin.applyManualMemo(String memo, Long updatedBy)` 시그니처 변경 — 수정자 ID 저장. `clearMemo()`도 `memoUpdatedBy = null` 리셋. `PinSummary` + `PinSummaryResponse`에 `memoUpdatedBy` / `memoUpdatedByNickname` 필드 추가. `PinService.toSummaries`는 `createdBy` + `memoUpdatedBy` ID를 1회 배치 쿼리로 닉네임 일괄 조회(N+1 방지). 프론트 `MapClient.authorLabel`: 수정자 != 등록자이면 수정자 닉네임, 동일하거나 null이면 등록자 닉네임으로 표시 — 커밋 6426914

- **Phase 2.8 완료**: 웹 등록 시 `instagramUrl` 명시 입력 UI (`MemoTagPanelContent` 공통, 검색·picker 양 경로 자동 커버). 클라이언트 `https://` 시작 검증 + 백엔드 `Pin.validateInstagramUrl` 양방향 보안 검증 + `PinCard.tsx` 조건부 href — [#21](https://github.com/rnqhstmd/wherewego/pull/21)
- **Phase 2.8 완료**: 핀 장소 정보(`place_name`, `address`) 텍스트 수정 — `PinUpdateCommand` 확장 + `PinEditDialog` 장소명/주소 편집 필드 (순서: 장소명 → 주소 → 태그 → 메모) + `PinListClient.applyPatch` 낙관적 반영 — [#21](https://github.com/rnqhstmd/wherewego/pull/21)
- **Phase 2.8 완료**: map ⋮ 메뉴 삭제 액션 — `PinPopup` footer에 HLine + 우측 정렬 텍스트 버튼, `PinDeleteConfirm` 재사용, `useOptimistic` reducer 일반화(`patch|remove`)로 마커 즉시 제거 + 실패 시 자동 롤백 — [#21](https://github.com/rnqhstmd/wherewego/pull/21)
- **Phase 8 완료**: 인앱 알림 트리거 (웹 직접 등록 경로) — `PinV1Controller.createPin`에서 `pinService.addPin` 완료 후 `notificationService.createForManualPin(groupId, userId, pinId)` 호출. 트랜잭션 분리(BR-3)를 위해 PinService 무변경 + Controller에 try-catch로 격리. 단건 핀 = 알림 1건, 유형 `MANUAL_PIN` — [#40](https://github.com/rnqhstmd/wherewego/pull/40)
- **Phase 9 완료**: 핀 공유 카드 — 지도 말풍선 팝업 ⋮ 버튼 **좌측에 sibling 공유 아이콘** 신설(메뉴 내 항목 아님). 클릭 시 바텀시트 오픈 → 1080×1350(4:5 세로) HTML Canvas 미리보기 + "복사하기"·"이미지 저장" 두 버튼(Web Share API 미사용, 사용자 결정). 카드: Mapbox Static API 지도(@2x 1024×1280 → drawImage 1080×1350 stretch) + off-canvas 2단계 `blur(8px)` 합성 배경 + 메모(히어로 44px) → 장소명(Bold 36px, 2줄 말줄임) → 날짜·주소(22px) → `written by {닉네임}` → 좌하단 "우리가갈지도" 워터마크. `navigator.clipboard.write(ClipboardItem)` + `<a download>` 분기, Pretendard self-host `@font-face` alias 등록, Canvas 즉시 메모리 해제, 8초 timeout + BR-6 단색 `#EAE4D4` 폴백. lib/share: `renderPinCard`·`mapboxStaticUrl`·`sanitizeFilename` + 단위 테스트 40건. BR-11/QE-3/AC-17(메모리 캐시) 폐기 — Mapbox CDN 캐시로 충분. 운영 후속: Mapbox Static CORS 실측 + 토큰 referrer 화이트리스트 — [#41](https://github.com/rnqhstmd/wherewego/pull/41)
- **Phase 2.10 완료**: 핀 장소 좌표 수정 — `PinUpdateCommand` 단일 `coordinateProvided` 플래그 + `latitude/longitude` BigDecimal 2필드 추가(8→11), `Pin.changeCoordinate` 도메인 메서드 신설, `PinV1Dto.UpdatePinRequest` 좌표를 BigDecimal 직접 매핑(CreatePinRequest 와 대칭), `PinPopup` ⋮ "좌표 수정" 진입점 + 신규 `PinCoordinateEditPicker` 시트(기존 picker 흐름 무영향), `useOptimistic patch` 좌표 반영(reducer 변경 없음), 진입 시 `flyTo` 로 마커 깜빡임 최소화. 삭제 핀 복원 기능은 제외(사용자 결정) — [#24](https://github.com/rnqhstmd/wherewego/pull/24)
- **Phase 10 완료**: 장소 방문 감지 — WISH/REEL 핀 100m 이내 30초 머무름 + GPS 정확도 50m 게이트 자동 감지(`useVisitDetection`). 1차 PATCH(tag→MEMORY) → 마커 하트 confetti 3개 + scale bounce 600ms → 메모 시트(선택). 후보 핀 firstEnterAt 병행 누적으로 차순위 즉시 토스트. 1차 실패 시 인라인 에러 토스트(1.5초 자동 닫힘). `PinUpdateResult` record로 태그 전이 정보 컨트롤러 전달 — [#57](https://github.com/rnqhstmd/wherewego/pull/57)

- **Phase 2.9 완료**: 핀 목록 API 페이지네이션 계약 — `GET /api/v1/groups/{groupId}/pins?page=&size=` + `totalCount`/`hasNext` 선택 응답 필드, 하위 호환 유지(파라미터 미전달 시 `{items}` 그대로). 부분 전달은 `PIN_PAGE_PARAM_INVALID`(400), `size>100`은 `PIN_PAGE_SIZE_EXCEEDED`(400), 비숫자 입력도 동일 매핑. `PinService.listGroupPinsPaged` 신규 + `PinRepository` 포트에 paged/count 메서드 4개 추가(Spring 의존 미노출). `/pins` UI 페이지네이션 컨트롤은 MVP 단계 ROI 낮아 본 Phase 제외(필요 시점에 별도 작업) — [#22](https://github.com/rnqhstmd/wherewego/pull/22)
