# memo 구현 추적

> PRD 요구사항별 구현 상태를 추적합니다.

## 범례

- ✅ 반영됨 — 코드에 구현 완료
- ⬜ 미반영 — 정책/설계만 확정, 코드 미구현

## 요구사항

| ID | 요구사항 | 상태 | PR/커밋 |
|----|----------|------|---------|
| FR-MMO-1 | 챗봇 2초 룰 자동 메모 매칭 (last_link 캐시) | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `TwoSecondMemoSession` Caffeine `expireAfterWrite(2s)`, key=botUserKey |
| FR-MMO-2 | 웹 UI 메모 수동 수정 (memo_source=MANUAL 저장) | ✅ | [#17](https://github.com/rnqhstmd/wherewego/pull/17) — Phase 2.6 PR-A: 지도 SpeechBubblePopup ⋮ 메뉴 인라인 편집(`PinPopupMemoEditor`), 빈 문자열 저장 시 잠금 해제(BR-3) |
| FR-MMO-3 | 수동 메모 존재 시 후속 자동 매칭 차단 | ✅ | [#5](https://github.com/rnqhstmd/wherewego/pull/5) — `PinJpaRepository.updateAutoMemoIfNotManual()` race-safe 조건부 UPDATE (WHERE `memo_source IS NULL OR memo_source <> 'MANUAL'`) |
| FR-MMO-4 | MANUAL이 AUTO를 덮어쓸 수 있는 권한 검사 (활성 GroupMember) | ✅ | [#17](https://github.com/rnqhstmd/wherewego/pull/17) — Phase 2.6 PR-A: 백엔드 `requireActiveMembership` 위임, 프론트 `GROUP_NOT_MEMBER` → "권한이 없어요" 매핑 |

## 후속 작업

- (없음 — Phase 2.6 PR-A에서 FR-MMO-2/4 완결)
