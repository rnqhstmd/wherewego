# 자기점검 결과 (phase-implement, 2026-06-12)

## 검증 방법
- 백엔드: PinVisitServiceIT 7 + GroupChatServiceIT(신규 1 포함) 직접 실행 — 27중 2실패는 develop 선행 실패(thumbnailUrl·rooms preview, 무관 확정). 신규 8케이스 전부 PASS
- iOS: Windows 빌드 불가 → 참조 심볼·계약 1:1·switch 전수 grep 검증(CI가 최종)

## AC 대조 (전 항목 충족)
- AC-1 체크인(태그 불변+SELF+PIN_VISIT 카드 무푸시) ✓ IT-1·IT-7
- AC-2 전환(MEMORY+confetti+흔적 시트+PIN_MEMORY 카드 푸시) ✓ IT-2 + submitVisit converted 분기
- AC-3 멱등+union+합산 토스트(카드 미적재) ✓ IT-3 + alreadyConverted 분기
- AC-4 TAGGED→SELF 승격(강등 없음) ✓ IT-4 + upsertVisit
- AC-5 아바타 스택 + 0명 생략 ✓ visitorsRow guard(nil/빈→행 생략, 최대 5+N)
- AC-6 VISIT_DETECTED 폐기(V023 DELETE+CHECK 재정의+코드·IT 제거, iOS grep 0건) ✓
- AC-7 1인 그룹 전환(countActiveByGroupId≤1 → soloGroup) ✓ IT-5
- AC-8 비멤버 동행 400·비활성 핀 404 ✓ IT-6

## 계약 정합
- DeclareVisitResponse{converted,alreadyConverted,visitors[]} ↔ iOS 1:1 ✓
- visitors[]·pinSnapshot·visitParticipants 전부 top-level 추가형(decodeIfPresent) ✓
- MessageKind switch 전수 6곳(앱 5+테스트 1) PIN_VISIT/PIN_MEMORY 처리 ✓

## Findings
- [Warning/설계 이탈(정당)] PinShareCard.swift 수정 — VisitToastView 삭제로 그 안의 formatDate/VisitDateFormatter 를 VisitCompanionSheet 로 이관하며 참조 갱신. 설계 변경 범위 외 파일이나 삭제 파생으로 불가피
- [Info] 신규 MessageKind 2종은 구버전 앱 디코드 실패 위험 — 서버·앱 동시 배포 전제(설계 §4, PR 본문 명시 예정)
- [Info] MockBean deprecation 경고 다수 — 기존 코드베이스 전반의 경고로 이번 변경과 무관

## QUESTION (phase-review 이월)
- 없음
