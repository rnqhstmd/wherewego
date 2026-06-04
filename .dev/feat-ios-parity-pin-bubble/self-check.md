# 자기점검 결과 (phase-implement, 1회 패스)

## Critical (2건 제기 → 판정)
- **[Critical/기각·오판] MapboxMapView.swift `Set<AnyCancelable>()`** — qa가 `AnyCancellable` 오타로 판단했으나, `AnyCancelable`은 **MapboxMaps SDK 고유 타입**(Combine의 AnyCancellable과 별개). develop 원본(line 103)에 이미 존재하고 `.observe{}.store(in:)`로 P4부터 Mac 빌드됨. 수정 시 오히려 깨짐 → **수정 안 함**.
- **[Critical/수정] 말풍선 앵커 + 배경탭 구조** — `BubbleOverlay`가 `PinBubbleView(...).position(x,y)` 호출하나 PinBubbleView가 전체화면 배경탭(`Color.clear.ignoresSafeArea`)을 포함 → `.position`이 배경탭까지 이동시키고 본체+꼬리 중심이 마커에 놓여 말풍선이 마커를 덮음(AC-2 위배). → coder 수정 위임(배경탭/본체 .position 단위 분리 + 본체를 마커 위로 높이 보정, GeometryReader/PreferenceKey).

## Warning (phase-review 이월)
- [Warning] PinBubbleView.swift:48-72 ScrollView clipShape — RoundedRectangle 클리핑이 ScrollView 내부 콘텐츠에 확실히 적용되는지(.clipped() 명시 권장). 말풍선 앵커 수정과 함께 검토.
- [Warning] PinDetailContent.swift:112 onChange(of: currentPin == nil) — 초기 진입 시 이미 nil이면 발동 누락 가능. 단 BubbleOverlay가 `selectedPin != nil`일 때만 렌더하므로 설계상 보호됨(방어적 .onAppear 체크 추가 여지).

## Info (참고)
- [Info] MapboxMapView screenPoint(for:) 주석 — "화면밖 raw 반환" vs "투영불가 nil" 혼용 표현 정리 권장(동작은 isPointVisible가 nil 처리로 올바름).
- [Info] PinBubbleView detailVM 순환참조 — PinDetailViewModel이 mapViewModel을 weak 보유(PinDetailViewModel.swift:40) 확인됨. 누수 없음.

## QUESTION (phase-review 이월)
- 없음 (qa의 QUESTION 1·2는 C1/C2와 동일 사안으로 오케스트레이터가 판정 완료).

## AC 코드레벨 체크 (qa)
AC-1·3·4·5·6·7·8·9·10·11·12·13·14 충족 확인. AC-2는 말풍선 앵커 수정 후 충족(코드 방향성) + DoD-B 시각 확정.
