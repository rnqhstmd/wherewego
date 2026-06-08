# 자기점검 결과

> oh-my-gx qa-manager가 산출물을 응답으로 반환하지 못해(3회 "네."만 응답), 오케스트레이터가 직접 검토로 대체함.

## CERTAIN (자동 수정 대상)
- 0건.

## 검증 내역
- **백엔드 컴파일**: `./gradlew compileJava compileTestJava` → BUILD SUCCESSFUL (exit 0, unchecked 경고만). `build(hits, url)` 시그니처 변경 + 호출부(BotChatProcessor, DemoSeedRunner) 정합.
- **iOS 시그니처 체인 일관**: PlaceCardsBubble.onSave(wishIDs, memo) → ReelSaveWizard.onSubmit → ChatMessageRow.onSavePlaceCards(cards, wishIDs, memo, sourceInstagramUrl) → ChatScrollContainer → BotChatView → BotChatViewModel.savePlaceCards(cards:wishIDs:memo:sourceInstagramUrl:). 테스트(PlaceCardSaveTests/BotChatViewModelTests)도 새 시그니처로 갱신됨.
- **savePlaceCards**(BotChatViewModel:199): BR-1(좌표없음 continue 스킵) / BR-2(좌표있는 카드 전부 저장, wishIDs.contains→WISH 아니면 REEL) / BR-3(memo 정규화 후 전체 핀 동일 적용) / BR-7(instagramUrl=sourceInstagramUrl, savedCount==0이면 saveResult.sourceInstagramUrl=nil로 보러가기 미노출) / AC-14(PLC_DUPLICATE_PIN catch 흡수, 결과 목록 미포함) 충족.
- **focusReel**(MapViewModel:422): focusedInstagramUrl 설정 → reloadPinsAppendOnly(방금 저장 핀 강제 반영) → 해당 url 핀 fitBounds. 핀 0건 no-op. visiblePins 필터(:179) = activeFilters && (focusedInstagramUrl==nil || instagramUrl==focused) 정합.
- **clearReelFocus**(:434): 배너 ✕에서만 호출(MainTabView:201). BR-4 준수.
- **ReelSaveWizard**(신규): 2스텝(위시 체크→메모), 좌표없음 카드 비활성+안내(AC-4), savableCards 0개 안내, 메모 trim 정규화, 제출 후 onClose. presentationDetents medium/large.
- **DeepLinkRouter.reelFocus**(:22) + **MainTabView**(consumePending :239, 배너 :143~201) 정합.

## QUESTION (phase로 이월 — 비차단)
- MapViewModel: 그룹 전환(switchTo)/탭 전환 시 focusedInstagramUrl이 유지된다. 이는 PRD BR-4("배너 ✕로만 해제")의 의도적 동작이나, 다른 그룹에는 해당 릴스 핀이 없어 "빈 지도 + 배너" 상태가 될 수 있다. 그룹 전환 시점에 한해 자동 해제가 UX상 더 자연스러울 수 있음(향후 판단). 현 구현은 PRD 준수이므로 비차단.
