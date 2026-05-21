# notification 구현 추적

> Phase 8 PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 기능 요구사항

| ID | 요구사항 | 상태 | PR |
|----|----------|------|-----|
| FR-1 | 웹 직접 등록 트리거 (PinV1Controller.createPin → createForManualPin) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-2 | 챗봇 3경로 트리거 (InstagramLinkHandler 3분기 + PlaceSelectionHandler), 4경로 자동 커버 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-3 | findOtherActiveMemberIds로 등록자 제외 수신자 fan-out | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-4 | autoRegistered 0건 시 알림 미생성 (NotificationService 자체 + 호출자 가드) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-5 | GET /api/v1/notifications/stream SSE 엔드포인트 + JWT 인증 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-6 | 30초 heartbeat (NotificationHeartbeatScheduler @Scheduled) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-7 | SSE 연결 중 push, 미연결 시 DB 저장만 + REST 조회 fallback | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-8 | 클라이언트 재연결 정책 (지수 백오프 2→30s, 최대 5회, failed 수렴) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-9 | 다중 탭 SSE 독립 연결 (CopyOnWriteArrayList) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-10 | GET /notifications 최신순 ≤50건 + unreadCount | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-11 | POST /read-all 전체 읽음 처리 (bulk UPDATE + clearAutomatically) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-12 | GET /notifications/{id} 단건 상세 (핀 목록 + deleted) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-13 | 모바일 우상단 [벨][프로필] 가로 배치 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-14 | 미읽음 시 빨간 점 8px + read-all 후 소멸 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-15 | 새 알림 말풍선 5초 자동 + 외부 탭 닫힘, 알림당 1회 (shownToastIds) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-16 | 벨 클릭 → 패널 + read-all 호출 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-17 | 패널 열림 중 새 알림 → 상단 추가 + read-all 재호출 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-18 | 핀 클릭 → 패널 닫힘 + flyTo + PinPopup | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-19 | 데스크탑 벨 슬롯 + 동일 UX | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| FR-20 | 빈 상태 안내 ("아직 알림이 없어요") | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |

## 비즈니스 규칙

| ID | 규칙 | 상태 | PR |
|----|------|------|-----|
| BR-1 | 등록자 본인 알림 미생성 (JPQL `userId <> :excludeUserId`) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| BR-2 | 영구 보관 (만료 정책 없음) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| BR-3 | 핀 트랜잭션과 알림 트랜잭션 분리 (호출자 try-catch + NotificationService @Transactional) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| BR-4 | 삭제 핀 알림 레코드 유지 + 상세에서 deleted=true (좌표 null) | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| BR-5 | autoRegistered 0건 / alreadySaved만 있는 경우 skip | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| BR-6 | SseEmitter 5분 타임아웃 | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |
| BR-7 | 알림 목록 최대 50건 cap | ✅ | [#40](https://github.com/rnqhstmd/wherewego/pull/40) |

## 후속 작업 (Trust Ledger 기록, 미반영)

- ⬜ `registeredBy` 필드 응답에서 제거 (FE 미사용, 최소 공개 원칙)
- ⬜ 조사 처리 자동화 (NotificationToast/Item — 받침 유무 을/를)
- ⬜ `notification_pins.pin_id` ON DELETE 정책 ADR 기록 (soft delete 영구 유지 가정)
- ⬜ SSE useEffect의 `markAllRead` 의존성 → `useRef` 패턴 전환
- ⬜ `NotificationPanel.loadDetail` 실패 시 에러 UI 표시
- ⬜ `NotificationPanel` 빠른 연속 클릭 시 detail fetch race condition (AbortController)
- ⬜ `MAX_EMITTERS_PER_USER=10` TOCTOU race 원자화 (`emitters.compute()` 블록)
- ⬜ `shownToastIds` LRU cap (장기 세션 메모리 증가 방어)

## 운영 검증 필요 (staging E2E)

- Vercel ↔ EC2 cross-origin EventSource 쿠키 전달 (SameSite=Lax 동작)
- Vercel/Cloudflare 프록시 SSE 스트리밍 버퍼링 (X-Accel-Buffering 효과 + 30초 heartbeat 즉시 도달)
- BFF SSE 전용 라우트 `request.signal` upstream 종료 동작
- 다중 탭 SSE 독립 수신 시나리오
