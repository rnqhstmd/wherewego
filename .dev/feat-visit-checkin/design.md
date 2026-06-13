# 설계서: 방문 체크인·추억 전환 정책 v2 (확정)

- 확정: 2026-06-12 (Q1 단일 API / Q2 신규 kind 2종 / Q3 늦은 제출 카드 미적재)
- PRD: `.dev/feat-visit-checkin/prd.md` · 정책 SSOT: `context/pin/visit-checkin-policy.md`
- 설계 규모: 중형. design-critic 반영: 시트 멤버 로드 실패 폴백(§2-1)

## 0. 설계 원칙

- **PIN_REPLY 선례 최대 재사용**: 채팅 카드 적재·pinSnapshot 배치 합성·핀 검증·푸시 분기 모두 #126 인프라 위에 얹는다.
- **추가형 API 계약**: `visitors[]`는 핀 응답 추가 필드(iOS `decodeIfPresent`).
- **registered 파생 동형**: visitors·카드 참여자는 페이지 조립 시 IN 배치 1회 합성(자기치유).
- **동시 배포 전제**: 신규 MessageKind 2종은 구버전 앱이 디코드 못 하므로 서버·앱 동시 배포(베타 수용, PR 본문에 명시).

## 1. 백엔드 (backend/apps/wherewego-api)

### 1-1. 마이그레이션 `V023__pin_visits_and_drop_visit_detected.sql`

```sql
CREATE TABLE pin_visits (
    id          BIGSERIAL PRIMARY KEY,
    pin_id      BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    visited_at  TIMESTAMP NOT NULL,
    source      VARCHAR(10) NOT NULL,   -- SELF | TAGGED
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_pin_visits UNIQUE (pin_id, user_id)
);
CREATE INDEX idx_pin_visits_pin ON pin_visits (pin_id);
DELETE FROM notifications WHERE type = 'VISIT_DETECTED';
DROP INDEX IF EXISTS uq_notifications_visit;
```
(기존 V0xx 파일들의 문법·네이밍 관례를 따른다. FK는 기존 테이블 관례에 맞춤 — 기존에 FK 제약을 안 걸었다면 동일하게 생략.)

### 1-2. 도메인: `PinVisit` 엔티티 + `PinVisitRepository`

- `domain/pin/PinVisit.java` — pin_id·user_id·visitedAt·`VisitSource source(SELF|TAGGED)`. BaseEntity 관례 따름.
- 리포: `findByPinIdIn(List<Long>)`(배치 합성용), `findByPinIdAndUserId`, save. upsert는 서비스 레벨(비관 락 안이라 select→insert/update로 충분, ON CONFLICT 불필요).

### 1-3. 방문 API — 단일 엔드포인트 (Q1 확정)

`POST /v1/groups/{groupId}/pins/{pinId}/visits` — body `{ "companionUserIds": [Long] }` (본인 제외 동행, 빈 배열/생략 = 혼자). 컨트롤러는 기존 PinV1Controller에 추가(경로 관례 일치).

`domain/pin/PinVisitService.declareVisit(userId, groupId, pinId, companionUserIds)`:
1. `requireActiveMembership` → `findActiveByIdAndGroupIdForUpdate`(비관 락 — 동시 제출 직렬화, 없으면 PIN_NOT_FOUND).
2. companions 검증: 자기 자신 포함 금지(포함되면 제거 후 진행 또는 400 — 단순화: 서버가 자동 제거), 그룹 활성 멤버가 아니면 400 `PIN_VISIT_COMPANION_INVALID`(ErrorType 신설).
3. **혼자(companions 빈) + 그룹 활성 멤버 ≥2** → 체크인: 태그 불변, 본인 SELF upsert(재방문=visitedAt 갱신, 기존 TAGGED면 SELF 승격), **PIN_VISIT 카드 적재**. 응답 `{converted:false, alreadyConverted:false, visitors}`.
4. **동행(비어있지 않음) 또는 1인 그룹 혼자(FR-I6)** →
   a. 본인 SELF + 타인 TAGGED upsert(union — 기존 SELF 행 강등 없음, 기존 TAGGED는 유지).
   b. 핀 WISH/REEL → `pin.changeTag(MEMORY)` + `converted=true` + **PIN_MEMORY 카드 적재**(payload에 참여 userIds 스냅샷).
   c. 이미 MEMORY → 태그 불변, `alreadyConverted=true`, **카드 미적재**(Q3 확정).
5. visitedAt = 서버 now(요청 바디로 받지 않음 — 감지 직후 호출이므로).
6. 응답 DTO: `{ converted, alreadyConverted, visitors: [{userId, nickname, profileImageUrl, source}] }` (프사는 GP-1 resolver).

기존 `updatePin` PATCH는 불변 — **수동 태그 편집은 visits 미적재**(정책 규칙 9: 수동 변경은 감지와 별개). `PinV1Controller` 139~143행의 `createForVisitDetected` fan-out만 제거, `transitionedToMemoryNow` 시그널은 유지(iOS 수동 전환 confetti 분기에서 계속 사용).

### 1-4. 채팅 카드 — 신규 MessageKind 2종 (Q2 확정)

- `MessageKind.PIN_VISIT`("다녀갔어요 📍")·`MessageKind.PIN_MEMORY`("함께 다녀왔어요 🎉"). payload: PIN_VISIT `{pinId}`, PIN_MEMORY `{pinId, userIds:[...]}`(그때 명단 스냅샷 — pin_visits 현재 상태가 아니라 payload 사용).
- 적재: `GroupChatService.appendVisitCard(groupId, visitorUserId, kind, pinId, participantUserIds)` **내부 전용 메서드 신설** — 발신자 = 방문자 명의. 공개 `postMessage`는 두 kind를 계속 400 거부(CHAT_KIND_INVALID — 사용자 입력 경로 차단).
- 프레임 조립: pinSnapshot IN 배치에 두 kind 합류(기존 `message.getKind() == PIN_REPLY` 조건 확장). PIN_MEMORY는 payload userIds → 프사 resolver로 top-level `visitParticipants:[{userId,nickname,profileImageUrl}]` 합성(registered 동형).
- rooms preview: PIN_VISIT → "장소에 다녀갔어요" / PIN_MEMORY → "추억을 남겼어요".
- 푸시(GroupChatService:304 분기): **PIN_MEMORY만 push, PIN_VISIT 제외**.
- 카드 적재는 핀 트랜잭션 commit과 같은 트랜잭션 내(동일 DB) — 실패 시 전체 롤백이 단순·일관. (S3 같은 외부 부수효과 없음.)

### 1-5. visitors[] 합류 (FR-B4)

핀 목록/단건 응답 조립 시 페이지 핀 IN 1회 `findByPinIdIn` + GP-1 프사 resolver → `PinV1Dto` summary에 `visitors[]` 추가 필드(구 클라 무시 가능).

### 1-6. VISIT_DETECTED 완전 제거 (FR-B6)

`NotificationService.createForVisitDetected`·`NotificationVisitWriter`(파일 삭제)·`NotificationType.VISIT_DETECTED`(enum 값 제거)·렌더 매핑(isVisitType·memo join·MEMORY 배지 경로)·`NotificationServiceVisitDetectedIT`(파일 삭제). V023이 행을 지우므로 enum 제거 안전.

### 1-7. 백엔드 테스트 (IT — Docker 기동 필수)

PinVisitServiceIT 신규: ① 체크인(태그 불변+SELF 적재+PIN_VISIT 카드) ② 동행 전환(MEMORY+TAGGED+PIN_MEMORY 카드+converted) ③ 늦은 제출(멱등 union+alreadyConverted+카드 미적재) ④ TAGGED→SELF 승격 ⑤ 1인 그룹 혼자=전환 ⑥ 비멤버 동행 400·비활성 핀 404 ⑦ 푸시 분기(PIN_VISIT 무푸시). GroupChatServiceIT: 두 kind 프레임 조립(visitParticipants·pinSnapshot) 1케이스. 기존 VisitDetected IT 삭제.

## 2. iOS (ios/WhereWeGo)

### 2-1. `VisitCompanionSheet.swift` (신규, VisitToastView 대체·삭제)

- "○○에 도착! 누구와 함께인가요?" — 🙋 혼자예요(즉시 제출) / 멤버 다중 선택 리스트(본인 제외, AvatarView+닉네임 체크) + "함께 다녀왔어요" 확인 버튼 / "나중에요" 닫기(현행 dismiss 의미 유지 — 세션 Set 유지).
- 멤버 목록: 시트 표시 시 `GroupAPI.members(groupId)` 로드. **로드 실패 폴백(critic 반영): "혼자예요"만 노출 + 멤버 목록 영역에 재시도 버튼**.
- 표시 트리거: 현행 `visitToastPinId` 그대로(MapView overlay → sheet 전환).

### 2-2. `MapViewModel.swift`

- `confirmVisit(pinId:)` → `submitVisit(pinId:companionIds:[Int])`로 교체: `pinAPI.declareVisit` 호출.
  - `converted` → 로컬 replacePin(tag=MEMORY)+confetti+`.visitMemo` 시트(기존 재사용).
  - `alreadyConverted` → "이미 추억으로 남긴 곳이에요 🎉 회원님의 방문도 기록했어요" 토스트.
  - 체크인(둘 다 false) → "다녀간 기록을 남겼어요 📍" 토스트(태그 불변).
  - 실패 → 현행 에러 규칙 그대로(세션 Set/firstEnterAt 유지 — 무한 재토스트 차단 주석 보존).
- visitors 로컬 반영: 응답 visitors로 해당 핀 patchLocal.

### 2-3. `PinAPI.swift`

- `PinSummary.visitors: [PinVisitor]?` (decodeIfPresent — 구서버 호환). `PinVisitor {userId, nickname, profileImageUrl}`.
- `declareVisit(groupId:pinId:companionUserIds:) -> DeclareVisitResponse {converted, alreadyConverted, visitors}`.

### 2-4. `PinDetailContent.swift` (보기 모드)

placeRow 아래 `visitorsRow` — AvatarView 18pt·-5 오버랩 스택(최대 5+이후 +N) + "N명이 다녀감". `visitors`가 nil/빈이면 행 생략(AC-5).

### 2-5. 채팅

- `ChatMessageModels.swift` MessageKind에 `PIN_VISIT`·`PIN_MEMORY` 추가 — ⚠️ **switch 전수 수정 필수**: `ChatMessageRow.body`·`BotChatViewModel.reconcileLatest`·`ChatMessageModels` payload 분기·`GroupChatModels` 분기·테스트 `makeFrame`(직전 CI 2연속 실패 교훈).
- `GroupChatModels.swift`: 두 kind 디코딩(payload 평탄화 없음 — pinSnapshot top-level 재사용) + `visitParticipants:[ChatVisitParticipant]?` top-level decodeIfPresent.
- `GroupMessageRow.swift`: 방문 카드 버블 — PIN_REPLY 버블 변형. 핀 카드(글리프+장소명+썸네일) 탭=`onOpenPin`(`.pinFocus`), 추억 핀 사진 제자리 펼침(matchedGeometry 선례), PIN_MEMORY는 카드 내 참여자 아바타 스택(18pt·-5, Q4: 아바타만·텍스트 명단 없음), 문구 "다녀갔어요 📍"/"함께 다녀왔어요 🎉".
- DMList preview는 서버 preview 문자열 그대로(클라 추가 작업 없음 — preview kind 매핑이 클라에 있으면 두 kind 추가).

### 2-6. 알림 제거

`NotificationAPI.swift` case VISIT_DETECTED 제거 + `NotificationInboxView.swift:290` 분기 제거(관련 배지·딥링크 경로 포함). 디코딩: 알림 type unknown-safe 처리가 없다면 — V023이 행을 지우므로 구 행 수신 없음, case 제거 안전.

### 2-7. iOS 테스트

MapViewModel submitVisit 3분기(converted/already/checkin) 스텁 테스트 + GroupChatModels 두 kind 디코딩 테스트 + makeFrame 전수 갱신. 빌드 검증은 CI(GitHub Actions).

## 3. 구현 순서 (배치 — 파일 배타)

- **B1 백엔드** (coder): §1-1→1-7 순. 검증 `(cd backend && ./gradlew test)` — Docker 기동, 선행 실패는 develop 워크트리 대조.
- **B2 iOS** (coder, B1 완료 후 — 응답 계약 확정 뒤): §2-1→2-7.
- 커밋: B1·B2 각 1커밋(gx-commit), PR 1개(base develop).

## 4. 리스크·결정 기록

- 동시 제출: 핀 비관 락으로 직렬화 — 멱등·union이 락 안에서 결정, race 없음.
- 신규 kind 2종: 구버전 앱 디코드 실패 위험 → 서버·앱 동시 배포 전제(베타 수용). PR 본문 명시.
- 수동 태그 편집(PATCH)은 visits 미적재 — "누가 갔는지" 모름. 아바타 스택은 선언 기반 기록만.
- VisitToastView 파일 삭제(대체) — 참조처 MapView overlay 1곳.
