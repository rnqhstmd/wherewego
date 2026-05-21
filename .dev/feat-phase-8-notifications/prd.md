# PRD: Phase 8 — 인앱 알림함

## 배경

현재 그룹원이 핀을 등록해도 상대방이 이를 인지하는 방법이 없다. 카카오톡 푸시(Phase 2 설계 당시 검토)는 도입하지 않기로 결정됐으며, 앱 내 알림함으로 대체한다. Phase 7까지 핀 등록 경로는 세 가지로 확정됐다: ① 챗봇 인스타그램 릴스 자동 처리(`InstagramLinkHandler` — 복수 핀 묶음), ② 챗봇 장소 카드 선택(`PlaceSelectionHandler` — 사용자 명시 선택, 단건), ③ 웹 직접 등록(`PinService.addPin` — 단건). 세 경로 모두 Phase 8 알림 트리거 대상이다. `IconBell`은 이미 `components/icons`에 존재하며, `SpeechBubblePopup` 스타일 말풍선 컴포넌트도 기존 구현이 있다.

## 목표

- 그룹원이 핀을 등록하면 상대방이 앱 내에서 실시간으로 인지할 수 있다
- 성공 지표: 알림 수신 후 지도 `flyTo` + 핀 팝업까지 끊김 없이 도달 가능

## 비목표 (Non-goals)

- 카카오톡 푸시 알림, 브라우저 Push API, 모바일 네이티브 알림
- 알림 삭제 기능 (읽음 처리만 지원)
- 알림 만료/정리 정책 (영구 보관, Phase 8 범위)
- 그룹 내 2인 초과 시나리오 (MVP는 2인 커플 그룹)
- 알림 내용 수정
- 핀 이외 이벤트(그룹 초대 등)의 알림

---

## 요구사항

### 기능 요구사항

**알림 생성**

- [Must] FR-1: 웹 직접 등록(`PinService.addPin`) 완료 후, 같은 그룹의 등록자 본인을 제외한 활성 멤버에게 알림 1건(`MANUAL_PIN` 유형)을 생성한다.
- [Must] FR-2: 아래 세 챗봇 경로에서 핀 저장 완료 시 상대방에게 `CHATBOT_PINS` 유형 알림 1건을 생성한다. 릴스·카드 선택 각각 1건의 알림으로 묶는다.
  - `InstagramLinkHandler` candidates 자동 저장 완료 시점 — 신규 저장된 핀 ID 목록 전체를 1건으로 묶음
  - `InstagramLinkHandler` TTL 만료 백그라운드 자동 저장(`autoSaveOnExpiry`) 완료 시점 — 동일 규칙 적용
  - `PlaceSelectionHandler` 장소 카드 선택 저장 완료 시점 — 단건 핀을 1개 묶음으로 처리
- [Must] FR-3: 알림 수신 대상은 같은 그룹의 활성 멤버 중 등록자 본인을 제외한 멤버다. (MVP 2인 구조에서는 상대방 1명)
- [Should] FR-4: `CHATBOT_PINS` 경로에서 `autoRegistered`가 0건이면(장소 저장 실패) 알림을 생성하지 않는다.

**SSE 실시간 전달**

- [Must] FR-5: `GET /api/v1/notifications/stream` SSE 엔드포인트를 제공한다. 인증된 사용자만 접속할 수 있으며, 미인증 요청은 401을 반환한다.
- [Must] FR-6: 서버는 30초마다 SSE heartbeat(comment 형식 `: heartbeat`)를 전송하여 연결을 유지한다.
- [Must] FR-7: 알림 생성 시 해당 수신자가 SSE에 연결돼 있으면 즉시 이벤트를 push한다. SSE 연결이 없으면 DB에만 저장한다(DB poll 방식 미지원 — 재접속 시 미읽음 목록 REST API로 조회).
- [Should] FR-8: 클라이언트는 SSE 연결이 끊기면 최대 5회, 지수 백오프(초기 2초, 최대 30초)로 재연결을 시도한다. 5회 실패 시 재연결을 멈추고 벨 아이콘을 "연결 끊김" 상태로 표시한다.
- [Should] FR-9: 사용자가 동시에 복수의 탭/창에서 접속하면 각 탭이 독립적으로 SSE를 연결하고 각각 이벤트를 수신한다.

**알림 목록 REST API**

- [Must] FR-10: `GET /api/v1/notifications` — 로그인 사용자의 알림 목록을 최신순으로 반환한다. 최대 50건. 페이지네이션은 이번 Phase 제외.
- [Must] FR-11: `POST /api/v1/notifications/read-all` — 로그인 사용자의 미읽음 알림을 전체 읽음 처리한다.
- [Should] FR-12: `GET /api/v1/notifications/{notificationId}` — 알림 단건 상세 조회 (포함된 핀 목록 반환).

**프론트엔드 UX**

- [Must] FR-13: 모바일 상단 내비게이션(`MobileTopNav`) 우상단에 벨 아이콘(`IconBell`)을 추가 배치한다. 기존 프로필 아이콘(설정 링크)은 유지하며, 벨(좌) + 프로필(우)로 가로 나란히 배치한다. 데스크탑에서는 `DesktopActionPill` 또는 사이드 영역에 벨 아이콘을 추가로 배치한다(FR-19). (Q1 결정 반영: 마이페이지 진입 경로 보존)
- [Must] FR-14: 미읽음 알림이 1건 이상이면 벨 아이콘 우상단에 빨간 점(지름 8px)을 표시한다. `POST /read-all`이 완료된 후 빨간 점을 제거한다.
- [Must] FR-15: SSE로 새 알림을 수신하면 벨 아이콘 옆에 말풍선을 노출한다. 말풍선은 5초 경과 또는 외부 탭/터치 발생 시 둘 중 먼저 도달하는 시점에 닫힌다. 말풍선 내용은 "[등록자 닉네임]님이 [장소명 첫 번째] 외 N곳을 저장했어요" 형식(핀이 1건이면 "외 N곳" 생략). 말풍선은 알림 1건당 딱 1회만 노출한다(재연결 후 이미 수신된 알림은 말풍선 재노출 안 함).
- [Must] FR-16: 벨 아이콘 클릭 → 알림 목록 패널(모바일: Sheet / 데스크탑: SidePanel) 노출. 패널 진입 시 `POST /read-all`을 호출하여 읽음 처리하고 빨간 점을 제거한다.
- [Must] FR-17: 알림 목록 패널이 열려 있는 상태에서 SSE로 새 알림이 수신되면 목록 상단에 즉시 추가한다. 빨간 점은 추가된 알림에 대해 즉시 갱신(표시)하고, `POST /read-all` 재호출로 읽음 처리한다.
- [Must] FR-18: 알림 목록에서 알림 항목 클릭 → 포함된 핀 목록(장소명, 주소) 표시. 핀 항목 클릭 → 패널 닫기 → 지도 `flyTo` + 해당 핀의 `PinPopup` 자동 표시.
- [Must] FR-19: 데스크탑(`DesktopActionPill` 또는 사이드바 영역)에도 벨 아이콘을 배치하여 모바일과 동일한 알림 UX를 제공한다. 배치 위치는 구현 단계에서 확정한다.
- [Should] FR-20: 알림 목록이 비어 있을 때 빈 상태 안내("아직 알림이 없어요")를 표시한다.

### 비즈니스 규칙

- [Must] BR-1: 등록자 본인에게는 알림을 생성하지 않는다. `registeredBy == receiverId`인 경우 알림 레코드를 생성하지 않는다.
- [Must] BR-2: 알림은 영구 보관한다. 만료 정책 없음. MVP 규모(2인 커플)에서 실질적 DB 부담 없으며, 향후 필요 시 별도 Phase에서 정책을 추가한다.
- [Must] BR-3: 핀 등록 후 알림 생성 실패(예: DB 오류)는 핀 등록 트랜잭션에 영향을 주지 않는다. 알림 생성은 핀 저장 커밋 이후 별도로 처리한다.
- [Must] BR-4: 핀이 이후 삭제되더라도 알림 레코드와 `notification_pins` 연결은 유지한다. 알림 상세 조회 시 삭제된 핀에 대해서는 "삭제된 장소"로 표시하고 `flyTo` 동작을 비활성화한다.
- [Should] BR-5: `CHATBOT_PINS` 유형에서 `autoRegistered` 0건이면 알림 생성을 skip한다. `alreadySaved`만 있는 경우도 skip 대상.
- [Should] BR-6: SSE `SseEmitter` 타임아웃은 5분으로 설정한다. 타임아웃 후 클라이언트는 재연결 정책(FR-8)에 따라 재연결한다.
- [Could] BR-7: 알림 목록 최대 표시 건수는 50건(최신순). 50건 초과분은 이번 Phase에서 표시하지 않는다.

### 품질 기대

- [Should] QE-1: 그룹원 핀 등록 후 상대방 SSE 수신까지 체감 지연이 없어야 한다(네트워크 정상 상태 기준).
- [Should] QE-2: SSE 연결/해제가 서버 메모리 누수를 유발하지 않아야 한다 — `SseEmitter` 완료/타임아웃/에러 시 `NotificationSseRegistry`에서 즉시 제거되어야 한다.

---

## 사용자 시나리오

**정상 흐름 — 챗봇 릴스 등록**

1. A가 카카오톡에서 인스타 릴스 URL 전송 → 핀 N개 자동 저장 완료
2. 서버: `NotificationService.createChatbotNotification(groupId, A의userId, [pinId1, pinId2, ...])`
3. 서버: B의 SSE 연결 확인 → 이벤트 push
4. B의 브라우저: 벨 아이콘 옆 말풍선 노출(5초 후 자동 닫힘) + 빨간 점 표시
5. B가 벨 클릭 → 알림 목록 패널 오픈 + 읽음 처리 → 빨간 점 소멸
6. B가 알림 항목 클릭 → 핀 목록(장소 N개) 표시
7. B가 장소 클릭 → 패널 닫힘 → 지도 `flyTo` + `PinPopup` 표시

**정상 흐름 — 챗봇 장소 카드 선택**

1. A가 카카오톡에서 복수 후보 카드 중 1개를 선택 → 핀 1건 저장 완료
2. 서버: `NotificationService.createChatbotNotification(groupId, A의userId, [pinId])` — 단건 묶음
3. 이후 릴스 등록과 동일

**정상 흐름 — 웹 직접 등록**

1. A가 웹에서 핀 직접 등록 (`POST /api/v1/groups/{groupId}/pins`)
2. 서버: `PinService.addPin` 완료 후 `NotificationService.createManualNotification(groupId, A의userId, pinId)`
3. 이후 동일

**정상 흐름 — 패널 열림 중 새 알림 수신**

1. B가 알림 패널을 열어 기존 알림을 확인 중
2. A가 새 핀 등록 → SSE 이벤트 push
3. B의 열린 패널 목록 상단에 새 알림 즉시 추가 + `POST /read-all` 재호출

**예외 흐름 — SSE 미연결 상태**

- B가 앱을 닫고 있는 경우: DB에만 알림 저장. B가 다음 접속 시 `GET /api/v1/notifications` 조회로 미읽음 알림 확인 + 빨간 점 표시.

**예외 흐름 — SSE 끊김 후 재연결**

- 재연결 성공 시: 이미 수신된 알림의 말풍선은 재노출하지 않음. 미읽음 상태(빨간 점)는 서버 상태 기준으로 유지.

**예외 흐름 — 핀 삭제 후 알림 조회**

- 알림 상세에서 해당 핀은 "삭제된 장소"로 표시. `flyTo` 버튼 비활성화.

---

## 영향 범위

- `PinService.addPin` — 알림 트리거 호출 추가 (기존 핀 등록 기능 무영향, BR-3)
- `InstagramLinkHandler.handleCandidates` / `handleLegacySingle` / `autoSaveOnExpiry` / `autoSavePreviousImmediately` — autoRegistered 결과 기반 알림 트리거 추가 (4개 경로 전체 커버)
- `PlaceSelectionHandler` — 장소 선택 저장 완료 후 알림 트리거 추가
- `GroupMemberRepository` — 같은 그룹 내 다른 활성 멤버 조회 메서드(`findOtherActiveMembers`) 신규 추가 필요
- `MobileTopNav` — 모바일 우상단 프로필 아이콘을 벨 아이콘으로 교체
- `DesktopActionPill` 또는 사이드바 — 데스크탑 벨 아이콘 추가
- `MapClient` — 알림 클릭 시 `flyTo` + `setSelectedPinId` 연동
- DB 스키마 — `notifications`, `notification_pins` 테이블 신규 (V007 Flyway)
- 기존 사용자 영향: 이미 등록된 핀에 대한 소급 알림 없음. V007 적용 즉시 신규 핀부터 알림 생성.

---

## 수용 기준

| # | 수용 기준 | 매핑 |
|---|----------|------|
| AC-1 | 웹에서 핀 등록 시 같은 그룹의 상대방에게 `MANUAL_PIN` 알림 1건이 DB에 생성된다 | FR-1, BR-1 |
| AC-2 | 챗봇 릴스 자동 저장으로 핀 N개 저장 성공 시 상대방에게 `CHATBOT_PINS` 알림 1건이 DB에 생성되며 `notification_pins`에 N개 행이 연결된다 | FR-2, BR-5 |
| AC-3 | 챗봇 장소 카드에서 사용자가 1건을 선택 저장하면 상대방에게 `CHATBOT_PINS` 알림 1건이 DB에 생성되며 `notification_pins`에 1개 행이 연결된다 | FR-2 |
| AC-4 | 챗봇 TTL 만료 백그라운드 자동 저장(`autoSaveOnExpiry`) 완료 시 상대방에게 `CHATBOT_PINS` 알림 1건이 생성된다 | FR-2 |
| AC-5 | `CHATBOT_PINS` 경로에서 `autoRegistered`가 0건이면 알림이 생성되지 않는다 | FR-4, BR-5 |
| AC-6 | 등록자 본인의 `userId`로는 알림 레코드가 생성되지 않는다 | FR-3, BR-1 |
| AC-7 | 미인증 사용자가 `GET /api/v1/notifications/stream`에 접속하면 401을 반환한다 | FR-5 |
| AC-8 | SSE 연결 중인 수신자에게 알림 생성 시 SSE 이벤트가 push된다 | FR-7 |
| AC-9 | SSE heartbeat가 30초 간격으로 전송된다 | FR-6 |
| AC-10 | `SseEmitter` 완료/타임아웃/에러 발생 시 `NotificationSseRegistry`에서 해당 emitter가 제거된다 | QE-2, BR-6 |
| AC-11 | `GET /api/v1/notifications`는 최신순 최대 50건의 알림 목록을 반환한다 | FR-10, BR-7 |
| AC-12 | `POST /api/v1/notifications/read-all` 호출 후 해당 사용자의 미읽음 알림이 모두 읽음 처리된다 | FR-11 |
| AC-13 | 미읽음 알림이 1건 이상이면 벨 아이콘에 빨간 점(지름 8px)이 표시된다 | FR-14 |
| AC-14 | 알림함 패널 진입 시 `POST /read-all` 완료 후 빨간 점이 사라진다 | FR-14, FR-16 |
| AC-15 | SSE로 새 알림 수신 시 말풍선이 노출되고, 5초 경과 또는 외부 탭/터치 시 둘 중 먼저 닫힌다 | FR-15 |
| AC-16 | 말풍선은 동일 알림에 대해 재연결 후에도 재노출되지 않는다 | FR-15 |
| AC-17 | 알림 목록 패널이 열려 있는 상태에서 SSE로 새 알림이 수신되면 목록 상단에 즉시 추가되고 `POST /read-all`이 재호출된다 | FR-17 |
| AC-18 | 알림 패널에서 핀 항목 클릭 시 패널이 닫히고, 해당 핀으로 지도 `flyTo`가 실행되며 `PinPopup`이 표시된다 | FR-18 |
| AC-19 | 핀 삭제 후 알림 상세에서 해당 핀이 "삭제된 장소"로 표시되고 `flyTo`가 비활성화된다 | BR-4 |
| AC-20 | 핀 저장 성공 후 알림 생성이 실패해도 핀은 정상 저장된 상태로 유지된다 | BR-3 |
| AC-21 | `SseEmitter` 타임아웃이 5분으로 설정된다 | BR-6 |
| AC-22 | 데스크탑 환경에서도 벨 아이콘 + 빨간 점 + 말풍선 + 알림 패널이 모바일과 동일하게 동작한다 | FR-19 |

---

## 제외 범위

- 알림 개별 삭제
- 알림 만료/자동 정리 스케줄러
- 50건 초과 시 페이지네이션
- 핀 이외 이벤트(그룹 초대, 멤버 탈퇴 등) 알림
- 수평 확장(다중 인스턴스) 지원 — 단일 EC2 기준 SseEmitter, Redis pub/sub 미도입(ADR-0001 유지)

---

## 엣지 케이스

1. **본인 등록 알림 차단**: A가 등록한 핀에 대해 A 자신에게 알림이 생성되지 않아야 한다.
2. **동시 다중 탭 SSE**: 동일 사용자가 2개 탭을 열면 `NotificationSseRegistry`가 userId별 복수 emitter를 관리하며 양쪽 탭 모두에 push한다.
3. **핀 등록 후 즉시 삭제**: 알림 레코드는 유지되며 상세에서 "삭제된 장소" 표시(AC-19).
4. **알림 생성 중 SSE 연결 해제**: DB 저장만 성공하면 됨. push 실패는 무시하며, 수신자가 재접속 시 REST 조회로 확인한다.
5. **챗봇 `autoSaveOnExpiry` 경로**: TTL 만료로 백그라운드에서 저장된 핀도 `CHATBOT_PINS` 알림 트리거 대상이다. `autoSavePreviousImmediately`(D 시나리오 이전 URL 즉시 저장) 경로도 동일하게 적용한다.
6. **미인증 SSE 접속**: 401 반환, emitter 등록 없음.
7. **그룹 2인 중 상대방이 아직 그룹 미가입**: 수신 대상 활성 멤버 조회 결과가 0건이면 알림 레코드를 생성하지 않는다.
8. **알림 패널이 열린 상태에서 새 알림 수신**: 목록 상단에 즉시 추가 + `POST /read-all` 재호출. 말풍선은 노출하지 않는다(패널이 이미 열려 있으므로).
9. **SSE 재연결 5회 실패**: 벨 아이콘을 "연결 끊김" 시각 상태로 표시. 새로고침 전까지 실시간 수신 불가임을 안내한다.
10. **`PlaceSelectionHandler` 중복 핀**: 이미 저장된 장소 선택 시 알림을 생성하지 않는다(`autoRegistered` 0건 규칙과 동일하게 적용).

---

## 의존성 / 리스크

| 항목 | 내용 |
|------|------|
| GroupMemberRepository 확장 | `findOtherActiveMembers(groupId, excludeUserId)` 메서드 신규 추가 필요. 현재 인터페이스에 없음. |
| InstagramLinkHandler 알림 트리거 진입점 다수 | `handleCandidates`, `handleLegacySingle`, `autoSaveOnExpiry`, `autoSavePreviousImmediately` 4개 경로 모두 커버해야 함. 누락 시 일부 경로에서 알림 미생성. |
| PlaceSelectionHandler 알림 트리거 | 장소 카드 선택 저장 완료 후 알림 생성 추가 필요. `InstagramLinkHandler`와 동일한 `CHATBOT_PINS` 유형으로 처리. |
| Spring SseEmitter 단일 인스턴스 | 추가 인프라(Redis pub/sub 등) 없이 단일 EC2 기준 동작. 수평 확장 시 적용 불가(ADR-0001 참조, MVP 범위에서 수용). |
| 알림 생성 트랜잭션 분리 | `PinService.addPin`은 `@Transactional`. 핀 커밋 후 알림 생성 실패가 롤백을 유발하지 않도록 별도 처리 필요(BR-3). |
| 데스크탑 벨 아이콘 배치 위치 | `DesktopActionPill` 레이아웃 변경 필요. 구체적 배치 위치는 구현 단계에서 확정. |

---

## Q&A 결정 사항 (확정)

| # | 질문 | 결정 |
|---|------|------|
| Q1 | 알림 보관 기간 | 영구 보관 (만료 정책 없음) |
| Q2 | 챗봇 PlaceSelectionHandler 경로 | 알림 포함 (CHATBOT_PINS 유형) |
| Q3 | 패널 열림 상태 새 알림 수신 | 목록 상단에 실시간 추가 + read-all 재호출 |
| Q4 | 데스크탑 벨 아이콘 | 이번 Phase 포함 |
| Q5 | 말풍선 자동 닫힘 | 5초 후 자동 닫힘 + 외부 탭(둘 중 먼저) |
