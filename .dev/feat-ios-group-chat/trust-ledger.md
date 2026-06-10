# Trust Ledger — GC-2 iOS 그룹 채팅

> 리뷰 수행: 오케스트레이터 직접(gx 읽기 에이전트 미반환 — 메모리 feedback_gx_dev_agents). Mechanical Gate=GitHub Actions CI 위임(Windows iOS 빌드 불가).

## QA (스펙 충족) — 통과
| 요구사항 | 충족 | 근거 |
|----------|------|------|
| FR-GC2-1 목록 | ✅ | DMListView/VM groupRooms·GroupRoomSummary·hasUnread |
| FR-GC2-2 멀티유저 방 | ✅ | GroupChatView·GroupMessageRow(senderUserId==currentUserId, 닉네임)·커서 페이징 |
| FR-GC2-3 3상태 버블 | ✅ | reelButton(registered→③ / 내+미등록→① / 남+미등록→②비활성) |
| FR-GC2-4 추출 팝업 | ✅ | register→extracting→wizard→savePlaceCards(409 흡수)→배너. 취소·0곳·502 분기 |
| FR-GC2-5 딥링크 | ✅ | .reelFocus(groupId:url:)·focusReel(groupId:) switchTo·enterGroup |
| FR-GC2-6 수신 | ✅ | 전송직후폴링(2s×10)+8s 라이브+scenePhase+willPresent 현재방 |
| FR-GC2-7 ShareExtension | ✅ | groupRooms/sendReelLink(엔드포인트만 교체, 멀티선택 유지) |
| FR-GC2-8 URL 감지 | ✅ | InstagramURL.isReelURL(단독 URL만, 백엔드 정규식 동치) |
| FR-GC2-9~11 셸 정리 | ✅ | 봇 문구 교체·봇 흔적 제거·탭명/헤더 "채팅" |

수용 기준 AC-1~8 전부 충족(봇 미노출·재공유 자동등록·타인 비활성·저장후 ③갱신·타그룹 전환·빈/에러·발신자 구분·URL 단독).

## 통합 감사 (보안/허점) — CRITICAL/HIGH 0건
- [정보/안전] 등록 권한: 서버 `sender_user_id==userId` 강제(CHAT_EXTRACT_FORBIDDEN 403). 클라 ① 버튼은 UI 가드일 뿐 서버 재검증 — 우회 불가.
- [정보/안전] registered: 서버 파생만 신뢰(클라 상태 컬럼/추정 없음). reconcile 교체-병합으로 자기치유.
- [정보/안전] URL 검증: 클라 InstagramURL(https+인스타 패턴, 2000자) + 백엔드 CHAT_REEL_URL_INVALID 이중. 2000자 초과 URL 은 TEXT 분기→TEXT 가드.
- [정보/안전] 탈퇴 발신자: senderUserId nil → 타인 취급 + "(알 수 없음)". 등록 버튼 미노출(isOutgoing false).
- [정보/안전] willPresent: roomId NSNumber 추출 실패/nil 시 배너 표시(안전 폴백). async 델리게이트로 Swift6 동시성 안전.
- [정보] 폴링 누수: send/live 폴링 task 모두 disappear 에서 취소.

## Info (수용 — 설계 명시)
- 딥링크 레벨0→레벨1 그룹 핀 1회 중복 로드(idempotent, 카메라는 focusReel.fitBounds 최종). DoD-B 시각 검증.
- 8s 라이브 폴링 + 전송직후 폴링 동시 reconcile 가능(중복 네트워크 무해).
- reconcile registered 갱신은 최신 페이지(20건) 범위(자기치유 정합).

## 미해결 Critical: 없음 → phase-complete 진행
