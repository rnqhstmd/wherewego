# Cross-Review 결과

- advisor: codex
- 브랜치: feat/phase-12-pin-experience-v2 (base: develop)
- DEV_DIR: .dev/feat-phase-12-pin-experience-v2
- 실행 시각: 2026-05-27

## AC 충족 매트릭스
| AC | 충족(O/X/부분) | 근거 (파일:라인 또는 PRD 인용) |
|----|--------|--------|
| AC-12-1 | O | `V012__pin_experience_v2.sql:16-30` |
| AC-12-2 | O | `V012__pin_experience_v2.sql:42-46`, `Pin.java:86-93` |
| AC-12-3 | O | `V012__pin_experience_v2.sql:93-98`, `UserModel.java:36` |
| AC-12-4 | O | `V012__pin_experience_v2.sql:68-88`, `NotificationType.java:12` |
| AC-12-5 | O | `WantService.java:54-85`, `WantToggleIT.java:131-155` |
| AC-12-6 | O | `WantService.java:61-63`, `PinPopup.tsx:468-504`, `WantToggleIT.java:209-224` |
| AC-12-7 | O | `WantService.java:65-85`, `WantToggleIT.java:192-206` |
| AC-12-8 | O | `Pin.java:289-299`, `WantService.java:86-95`, `WantToggleIT.java:157-180` |
| AC-12-9 | O | `WantService.java:68-95`, `WantToggleIT.java:182-206` |
| AC-12-10 | O | `WishConvertedNotificationListener.java:28-37`, `NotificationService.java:121-160`, `NotificationItem.tsx:23-33` |
| AC-12-11 | O | `WantService.java:95`, `PinV1Dto.java:73-80` |
| AC-12-12 | O | `PinSummary.java:13-31`, `PinService.java:51-72`, `PinV1Dto.java:38-60` |
| AC-12-13 | O | `PinV1Controller.java:61-109`, `PinService.java:217-236,258-283`, `PinRepositoryImpl.java:138-177` |
| AC-12-14 | O | `PinV1Controller.java:69-70`, `PinService.java:222-225`, `PinJpaRepository.java:103-126` |
| AC-12-15 | O | `markers.tsx:30-35,224-228`, `MapboxView.tsx:226-241` |
| AC-12-16 | O | `markers.tsx:30-35,224-228`, `MapboxView.tsx:236-241,470-485` |
| AC-12-17 | O | `markers.tsx:30-35,224-228`, `MapboxView.tsx:243-248` |
| AC-12-18 | X | PRD §5 AC-12-18 "`해당 마커에 0.5초 CSS 펄스 애니메이션이 1회`"; `MapClient.tsx:277,549-550,570-578,2095`는 `pulse`를 `PinPopup`으로만 넘기고, `PinPopup.tsx:54-58,79-83`은 미사용이라 주석 처리, `MapboxView.tsx:211-270,465-513`에도 pulse 분기 없음 |
| AC-12-19 | 부분 | PRD §5 AC-12-19 "`카드/말풍선에는 📹 뱃지, 없는 핀에는 ✏️ 뱃지`"; `PinPopup.tsx:404-442`는 충족, `PinCard.tsx:78-90`은 `Instagram` 링크만 있고 📹/✏️ 뱃지 없음 |
| AC-12-20 | O | `InstagramLinkHandler.java:201,218-219`, `ReelSavedSelectionSession.java:22-29` |
| AC-12-21 | O | `ReelMultiSelectionHandler.java:82-99,105-109,143-148`, `ReelMemoWaitingHandler.java:160-211` |
| AC-12-22 | O | `ReelCommaParser.java:87-106`, `ReelMultiSelectionHandler.java:111-121` |
| AC-12-23 | O | `ReelCommaParser.java:77-106` |
| AC-12-24 | O | `InstagramLinkHandler.java:226-238`, `ReelBulkSaveHandler.java:68-94`, `ReelMemoWaitingHandler.java:167-170` |
| AC-12-25 | O | `CacheConfig.java:91-113`, `ReelSelectionAutoSaveScheduler.java:113-156` |
| AC-12-26 | O | `ChatbotWebhookService.java:126-194` |
| AC-12-27 | O | `ChatbotWebhookService.java:112-123` |
| AC-12-28 | O | `ReelMemoWaitingHandler.java:117-125,200-204` |
| AC-12-29 | O | `ReelSingleWantHandler.java:80-95`, `ReelMemoWaitingHandler.java:208-211` |
| AC-12-30 | O | `CacheConfig.java:91-113`, `ReelMultiSelectionHandler.java:31-33`, `ReelSelectionAutoSaveScheduler.java:113-156` |
| AC-12-31 | O | `PinJpaRepository.java:79-84`, `CleanupService.java:54-71`, `CleanupBanner.tsx:22-24,54,95` |
| AC-12-32 | O | `CleanupService.java:79-90`, `CleanupBanner.tsx:112` |
| AC-12-33 | O | `CleanupService.java:97-104`, `UserModel.java:89-98`, `CleanupBanner.tsx:120`, `me-client.ts:29-33` |
| AC-12-34 | O | `V012__pin_experience_v2.sql:93-98`, `UserModel.java:36,89-98`, `UserCleanupSnoozeV1Controller.java:19,30` |
| AC-12-35 | O | `TagFilterButton.tsx:38-46,211-235`, `MapClient.tsx:1284`, `MapboxView.tsx:470-485` |
| AC-12-36 | O | `NotificationPinList.tsx:40-50,108-135`, `MapClient.tsx:271-273,595-616,1292-1299,1966-2011`, `MapboxView.tsx:61-68,380-389,468-504` |
| AC-12-37 | 부분 | PRD §5 AC-12-37 "`핀 카드/말풍선의 ? 아이콘`"; `PinPopup.tsx:443-466,626-632`, `TagProgressModal.tsx:79-108`는 충족, `PinCard.tsx:47-110`에는 `?` 아이콘/모달 진입점 없음 |

[Must] 28/29 충족, [Should] 6/8 충족, 부분 충족 2건, 미충족 1건.

## 설계 범위 이탈
- 이탈 파일 1: `bash.exe.stackdump` / 설계서 §2 변경 범위 매트릭스에 없는 런타임 덤프 파일이다. 구현 산출물이라기보다 작업 중 우발 생성물로 보인다.
- 이탈 파일 2: `backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/RequestIdFilterConfig.java` / §2에 보안 필터 설정 변경 항목이 없다. `ReelSelectionAutoSaveScheduler`의 MDC 연계성 보정 같은 부수 수정으로 추정된다.
- 이탈 파일 3: `backend/apps/wherewego-api/src/main/java/com/wherewego/monitoring/ThresholdMonitorScheduler.java` / §2 범위(핀, 알림, 챗봇, cleanup, 프론트)에 직접 포함되지 않은 모니터링 스케줄러다.
- 이탈 파일 4: `context/README.md`, `context/*/status.md`, `context/pin/architecture.md`, `context/pin/phase-12-pin-experience-v2.md`, `docs/superpowers/specs/2026-05-26-pin-experience-v2-design.md` / 설계 산출물 동기화성 문서 변경으로 보이며 런타임 구현 범위는 아니다.
- 이탈 파일 5: 테스트 파일 일괄(`*Test.java`, `*.test.tsx`) / 설계서 §2 매트릭스가 프로덕션 파일 중심으로만 기술되어 테스트 범위가 누락된 것으로 보인다.

## 신규 위험
### Critical
없음.

### Warning
- [GAP] WISH 전환 펄스가 실제 마커에 연결되지 않아 AC-12-18이 미충족이다.
  위치: `frontend/src/app/map/MapClient.tsx:277,549-550,570-578,2095`, `frontend/src/app/map/_components/PinPopup.tsx:54-58,79-83`, `frontend/src/app/map/_components/MapboxView.tsx:211-270,465-513`
  근거: PRD §5 AC-12-18 "`해당 마커에 0.5초 CSS 펄스 애니메이션이 1회 재생`". 현재 `pulse`는 `PinPopup` prop으로만 전달되고, `PinPopup` 주석이 "추후 배치"라고 명시하며 미사용이다. 지도 마커 렌더러 `MapboxView`에도 pulse 분기가 없다.
  권고: `MapboxView`의 marker DOM 또는 `SpeechBubblePopup -> PinDot`에 `pin-pulse-once`를 실제 연결하고 500ms 후 제거하도록 보완할 것.

- [GAP] PinCard에 출처 뱃지가 없어 AC-12-19가 부분 충족에 그친다.
  위치: `frontend/src/app/map/_components/PinPopup.tsx:404-442`, `frontend/src/app/pins/_components/PinCard.tsx:78-90`
  근거: PRD §5 AC-12-19 "`instagram_url`이 있는 핀의 카드/말풍선에는 📹 뱃지, 없는 핀에는 ✏️ 뱃지". 말풍선은 구현됐지만 PinCard는 텍스트 링크만 있고 📹/✏️ 뱃지가 없다.
  권고: PinCard에도 동일한 출처 뱃지 UI를 추가해 카드/말풍선 일관성을 맞출 것.

- [GAP] PinCard에 `?` 아이콘과 진행 모달 진입점이 없어 AC-12-37이 부분 충족이다.
  위치: `frontend/src/app/map/_components/PinPopup.tsx:443-466,626-632`, `frontend/src/app/pins/_components/PinCard.tsx:47-110`
  근거: PRD §5 AC-12-37 "`핀 카드/말풍선의 ? 아이콘 클릭 시 태그 진행 다이어그램 모달`". 말풍선은 `TagProgressModal`로 연결돼 있으나 PinCard 쪽 구현은 없다.
  권고: PinCard에 `?` 버튼과 `TagProgressModal` 상태를 추가해 두 진입면의 약속을 동일하게 맞출 것.

### Info
없음.

## 자기점검·phase-review 수정 검증
| 수정 항목 | 검증 결과 (O/부분/X) | 근거 |
|----------|----------------------|------|
| 자기점검 #1: AC-12-21 MULTI_SELECTING WANT 적용 | O | `ReelMultiSelectionHandler.java:82-99,105-109,143-148`, `ReelMemoWaitingHandler.java:180-211` |
| 자기점검 #2: AC-12-36 reel_bundle opacity 0.3 | O | `MapboxView.tsx:61-68,380-389,468-504`, `MapClient.tsx:1292-1299` |
| 자기점검 #3: WantService.getStatus 락 오용 | O | `WantService.java:102-110` |
| review #1: MapboxView 마커 wantCount 분기 | O | `MapboxView.tsx:470-485`, `markers.tsx:224-228` |
| review #2: Caffeine TTL 갱신 방지 (Expiry 커스텀) | O | `CacheConfig.java:91-113` |
| review #3: WISH_CONVERTED reel_bundle 제거 | O | `NotificationPinList.tsx:40-50,108-112` |
| review #4: CHATBOT_PINS/WISH_CONVERTED 분리 알림 의도 주석 | O | `ReelMemoWaitingHandler.java:133-140` |

## 총평
- 강점: WANT 토글의 DB/도메인/AFTER_COMMIT 알림 경로는 `V012__pin_experience_v2.sql`, `WantService.java`, `WishConvertedNotificationListener.java`가 PRD 핵심 약속을 대체로 정확히 지킨다.
- 강점: 챗봇 v2는 상태머신, 콤마 파서, TTL 고정 Expiry, 자동 저장 스케줄러까지 이어지는 경로가 일관되고 자기점검·phase-review 수정도 실제 코드에 반영돼 있다.
- 합산: Critical 0건, Warning 3건, Info 0건
- 권고: 이번 브랜치는 머지 전 최소한 `AC-12-18`, `AC-12-19`, `AC-12-37`의 프론트 누락 3건을 우선 보완하는 편이 안전하다.
