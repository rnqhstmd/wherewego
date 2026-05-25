# 챗봇 응답 엣지 케이스 긴급 수정

## 배경

**사용자 보고:** 사용자가 인스타 릴스 링크 전송 후 메모 입력 시 봇 응답이 없었고, 이후 다른 텍스트("ㅊㅊ")를 보내자 fallback 응답과 함께 "🔗 그룹 연동하기" QuickReply가 노출됨. 이미 그룹 연동이 완료된 상태임에도 재연동을 유도하는 것처럼 보여 사용자 혼란 발생.

**진단 결과:**
- `UnknownHandler`: 미분류 발화 전체에 연동 여부 무관하게 동일 fallback + "🔗 그룹 연동하기" QuickReply 노출
- `InstagramLinkHandler.processWithMemoAsync`: useCallback push 실패 시 body가 비어 있으면 `PendingNotificationSession` 적재를 건너뜀 → silent failure
- `InstagramPendingMemoHandler`: pending 만료 후 메모가 도착하면 짧은 안내만 반환하고 사용자 발화를 silent drop
- `InstagramPendingMemoHandler`: pending 상태에서 "그룹 연동하기" utterance가 메모로 저장됨

---

## 요구사항

### FR-1 [Must] UnknownHandler 상태별 분기 (P0-1)

**현재 거동:** 미분류 발화(UNKNOWN MessageType) 수신 시 사용자 상태와 무관하게 단일 fallback 메시지 + "🔗 그룹 연동하기" QuickReply를 항상 반환.

**변경 거동:** botUserKey 기준으로 사용자 상태를 4가지로 분류하여 각각 다른 응답을 반환.

| 상태 | 조건 | 응답 | QuickReply |
|------|------|------|-----------|
| 미연동 | 연동된 그룹 없음 (`BotUserMappingService.resolveUserId` 부재) | "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요. 그룹 연동이 필요하면 아래 버튼을 눌러주세요." | 🔗 그룹 연동하기 |
| 연동·pending 없음 | 연동 완료, 활성 pending 없음 | "인스타그램 릴스 링크를 보내면 장소가 자동으로 저장돼요. 혹시 링크를 보내셨다면 처리 중일 수 있어요." | 없음 |
| 연동·pending 있음 | 연동 완료, 활성 `PendingInstagramSession` peek 존재 | "메모 입력을 기다리고 있어요. 메모를 보내거나 ❌ 메모 없이 저장을 눌러주세요." | ❌ 메모 없이 저장 |
| 연동·최근 자동저장 | 연동 완료, 활성 `RecentlyAutoSavedSession` 존재 (peek 가능) | "방금 장소가 자동 저장됐어요. 다른 링크를 보내면 계속 저장할 수 있어요." | 없음 |

**변경 파일:** `UnknownHandler.java`, 필요 시 `ChatbotWebhookService.java` (botUserKey 전달 경로 확보).

> 우선순위: pending 있음 > 최근 자동저장 > pending 없음. 한 사용자가 여러 조건에 동시 해당하면 위에서 가까운 분기를 선택한다.

---

### FR-2 [Must] useCallback push 실패 시 fallback 강제 적재 (P0-2)

**현재 거동:** `InstagramLinkHandler.processWithMemoAsync`에서 useCallback push 실패 후 `bodyText`가 비어 있으면 `PendingNotificationSession` 적재 조건(`!pushed && bodyText != null && !bodyText.isBlank()`)을 통과하지 못해 어떠한 후속 처리도 없이 종료.

**변경 거동:** push 실패(pushed = false) 시 `bodyText` 값과 무관하게 `PendingNotificationSession`에 fallback 텍스트("장소 저장 처리 중 문제가 발생했어요. 잠시 후 다시 확인해 주세요.") 적재. bodyText가 정상이면 기존 prefix를 그대로 사용한다.

**변경 파일:** `InstagramLinkHandler.java`

---

### FR-3 [Must] pending 만료 후 메모 silent drop 차단 (P0-3)

**현재 거동:** `InstagramPendingMemoHandler`에서 `pendingOpt.isEmpty()` 분기 진입 시 짧은 안내 메시지만 반환하고 사용자가 보낸 발화 내용을 응답에 포함하지 않음 → 사용자는 자신의 메모가 처리되었는지 알 수 없음.

**변경 거동:**
- 사용자 발화(utterance)를 echo back하여 "메모가 너무 늦게 도착했습니다"를 명시적으로 안내. 예: "'{utterance}' 메모를 받았지만, 링크 처리가 이미 완료되어 메모를 저장할 수 없었어요. 앱에서 직접 추가할 수 있어요."
- `RecentlyAutoSavedSession`이 존재하면 최근 자동저장된 장소명을 함께 안내. 예: "방금 '{장소명}'이 메모 없이 자동 저장됐어요."

**변경 파일:** `InstagramPendingMemoHandler.java`

> RecentlyAutoSavedSession은 URL 기반이라 직접 peek이 어려운 경우, 사용자별 최근 URL을 별도 캐시로 관리하지 않고 안내 텍스트만 일반화한다 (구현 단계에서 결정).

---

### FR-4 [Should] 메모 입력 중 "그룹 연동하기" QuickReply 오용 차단 (P1)

**현재 거동:** `INSTAGRAM_PENDING_MEMO` 상태에서 사용자가 "그룹 연동하기" QuickReply를 누르면 utterance = "그룹 연동하기" 텍스트가 메모로 저장됨.

**변경 거동:** utterance가 "그룹 연동하기"(정확 일치)이면 메모 저장 로직을 실행하지 않고, pending 상태를 유지한 채 다음 메시지 반환. "지금은 메모 입력 중이에요. 메모를 보내거나 ❌ 메모 없이 저장을 눌러주세요." QuickReply: ❌ 메모 없이 저장.

**변경 파일:** `InstagramPendingMemoHandler.java`

---

## 수용 기준

- **AC-1 [FR-1]** 연동 사용자가 "ㅊㅊ" 등 분류 불가 발화를 전송하면, 응답 QuickReply 목록에 "🔗 그룹 연동하기"가 포함되지 않는다.
- **AC-2 [FR-1]** 미연동 사용자가 분류 불가 발화를 전송하면, 응답에 "🔗 그룹 연동하기" QuickReply가 포함된다.
- **AC-3 [FR-1]** `PendingInstagramSession`이 활성 상태인 연동 사용자가 분류 불가 발화를 전송하면, 응답 텍스트에 "메모 입력을 기다리고 있어요" 문구가 포함되고 QuickReply로 "❌ 메모 없이 저장"이 제공된다.
- **AC-4 [FR-2]** useCallback push가 실패하고 bodyText가 빈 값인 조건에서도, 해당 사용자의 다음 발화 응답 앞에 "장소 저장 처리 중 문제가 발생했어요." 텍스트가 prepend된다.
- **AC-5 [FR-2]** push가 성공한 경우 `PendingNotificationSession` 적재 로직이 실행되지 않고 기존 흐름과 동일하게 처리된다.
- **AC-6 [FR-3]** `PendingInstagramSession`이 없는 상태에서 메모 텍스트가 수신되면, 응답 텍스트에 사용자가 보낸 발화 내용이 인용 형태로 포함된다.
- **AC-7 [FR-3]** pending 없음 + RecentlyAutoSaved 있음 → 응답에 최근 자동저장된 장소명이 포함된다.
- **AC-8 [FR-4]** `INSTAGRAM_PENDING_MEMO` 상태에서 utterance가 정확히 "그룹 연동하기"인 경우, 장소 저장 로직이 실행되지 않고 pending 상태가 유지되며 "지금은 메모 입력 중이에요." 안내가 반환된다.
- **AC-9 [FR-4]** utterance가 "그룹 연동하기"가 아닌 경우, 기존 메모 저장 흐름이 그대로 실행된다.
