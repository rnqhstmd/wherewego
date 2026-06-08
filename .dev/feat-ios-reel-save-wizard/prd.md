# PRD: iOS 봇 릴스 장소 저장 위저드 (위시/발견 + 메모 + 보러가기)

## 배경

iOS 인앱 봇 채팅("어디가지 봇")에서 인스타 릴스 URL을 전송하면 BotChatProcessor가 장소를 추출하여 PLACE_CARDS 버블을 반환한다. 현재 PlaceCardsBubble은 체크박스 다중선택 후 "N곳 저장" 버튼으로 선택한 카드를 tag=REEL 단일 분류로 저장하며, 위시 분류·메모 입력이 없고 instagramUrl=nil로 비워 저장한다. PLACE_CARDS payload에도 출처 URL 필드가 없어 저장된 핀이 어떤 릴스에서 왔는지 추적할 수 없다.

개선 목표: 저장 UX를 2스텝 바텀시트 위저드로 전환하여 위시/발견 분류 선택과 메모 입력을 지원하고, 저장 완료 후 해당 릴스 핀만 지도에서 바로 확인하는 흐름을 추가한다.

---

## 요구사항

### Must

**백엔드**

- FR-B1: BotPlaceCardsPayloadBuilder.build()가 sourceInstagramUrl 파라미터를 받아 PlaceCardsPayload에 포함하여 반환한다. PlaceCardsPayload 레코드에 `String sourceInstagramUrl` 필드를 추가한다.
- FR-B2: BotChatProcessor.doProcess()가 파싱된 url 값을 payloadBuilder.build(hits, url)로 전달한다. 기존 build(hits) 단일 파라미터 호출을 교체한다.

**iOS — 데이터 모델**

- FR-I1: ChatMessageModels.swift의 PlaceCardsPayload에 `sourceInstagramUrl: String?` 디코딩 필드를 추가한다. ChatFrame PLACE_CARDS 분기 디코딩에서 해당 값을 placeCards와 함께 추출하여 ChatFrame에 노출한다.

**iOS — 저장 위저드**

- FR-I2: PLACE_CARDS 버블을 탭하면 기존 인라인 PlaceCardsBubble UI 대신 2스텝 바텀시트 위저드를 표시한다.
- FR-I3: Step 1/2 (위시 고르기): 추출된 장소 목록과 체크박스를 표시한다. 체크=WISH로 저장, 미체크=REEL로 저장. 좌표 없는 카드는 선택 비활성 처리하고 저장 불가 안내 문구를 표시한다. [다음] 버튼으로 Step 2로 이동한다.
- FR-I4: Step 2/2 (메모, 선택): 메모 입력 텍스트필드를 표시한다. [← 이전] / [건너뛰기] / [저장] 버튼을 제공한다. 메모는 선택 사항이며 [건너뛰기]는 메모 없이 저장을 실행한다.
- FR-I5: 저장 실행 시 좌표 있는 카드 전부를 저장한다. 체크한 카드는 tag=WISH, 미체크 카드(좌표 있는 것)는 tag=REEL로 저장한다. 메모가 있으면 저장되는 모든 핀에 동일하게 적용한다. 각 핀의 instagramUrl에 sourceInstagramUrl을 기록한다.
- FR-I6: 409(PLC_DUPLICATE_PIN) 응답은 에러로 전파하지 않고 흡수한다.

**iOS — 저장 완료 결과 버블**

- FR-I8: 저장 완료 후 채팅 뷰에 결과 버블을 표시한다. 버블 내용: "✨ 위시 N곳 · 📍 발견 M곳 저장했어요" + 신규 저장 성공 장소 이름 목록(409 중복 제외) + (메모 있으면) 메모 + [지도에서 보기 →] 버튼. 버블의 N+M과 목록 건수가 일치한다.

**iOS — 지도 포커스**

- FR-I10: [지도에서 보기 →] 버튼 탭 시 채팅 탭 → 지도 탭으로 전환한다.
- FR-I11: 지도 탭 전환 후 해당 릴스 instagramUrl 기반으로 핀을 필터링한다. 해당 instagramUrl을 가진 핀만 지도에 표시한다.
- FR-I12: 필터된 핀이 한 화면에 모두 보이도록 fitBounds를 실행한다.
- FR-I13: 지도 상단에 배너를 표시한다. 배너 문구: "🎬 이 릴스에서 저장한 N곳 · 전체 보기 ✕". ✕ 탭 시 필터를 해제하고 배너를 닫는다.

**iOS — DeepLinkRouter 및 MainTabView 연동**

- FR-I15: DeepLinkRouter에 `.reelFocus(instagramUrl: String)` destination을 추가한다. BotChatViewModel이 [지도에서 보기 →] 탭 이벤트를 트리거하면 이 destination을 통해 MainTabView가 소비한다.
- FR-I16: MainTabView의 consumePending()이 `.reelFocus` destination을 처리한다. 지도 탭 전환 + mapViewModel.focusReel(instagramUrl:) 호출을 수행한다.

**비즈니스 규칙**

- BR-1: 좌표 없는 카드는 저장 대상에서 제외한다. 위저드 Step 1에서 선택 비활성, 저장 실행 시에도 스킵한다.
- BR-2: 저장 실행 대상은 체크 여부와 무관하게 좌표 있는 카드 전부다. 체크 여부는 tag(WISH/REEL)만 결정한다.
- BR-3: 메모는 저장되는 모든 핀에 동일하게 적용된다. 핀별 개별 메모는 이번 범위 밖이다.
- BR-4: instagramUrl 기반 지도 필터는 배너 ✕로만 해제된다. 탭 전환, 앱 재진입 등 다른 동작으로는 해제되지 않는다.
- BR-5: 카카오톡 챗봇(domain/chatbot/*)은 변경하지 않는다.
- BR-6: 백엔드 DB 컬럼·마이그레이션·신규 엔드포인트를 추가하지 않는다. POST /pins는 이미 instagramUrl·memo·tag를 지원하므로 변경 불필요.
- BR-7: sourceInstagramUrl이 nil인 경우(구버전 백엔드 응답 호환) 저장은 정상 진행하되 instagramUrl=nil로 저장하고 [지도에서 보기 →] 버튼은 미표시한다.

### Should

- FR-I7: 바텀시트 위저드 헤더에 단계 표시("1/2", "2/2")를 노출한다.
- FR-I14: instagramUrl 기반 필터를 MapViewModel에 focusedInstagramUrl 상태로 관리한다. 배너 ✕ 외의 어떤 동작으로도 해제되지 않는 영속 필터다.

### Could

- FR-I17: PlaceCardsBubble 헤더 문구 "이런 장소를 찾았어요"를 더 자연스러운 표현으로 교체한다.
- FR-I18: 봇 채팅 빈 화면 안내 문구, 추출 결과 헤더, 단계 안내, 저장 버튼 문구를 더 자연스럽게 개선한다.

---

## 수용 기준

- AC-1: 인스타 릴스 URL 전송 후 봇의 PLACE_CARDS 응답 payload에 sourceInstagramUrl 필드가 포함된다. → [FR-B1, FR-B2]
- AC-2: iOS ChatFrame 디코딩 시 PLACE_CARDS kind에서 sourceInstagramUrl을 읽어낸다. → [FR-I1]
- AC-3: PLACE_CARDS 버블을 탭하면 기존 인라인 체크박스 UI 대신 바텀시트 위저드가 열린다. → [FR-I2]
- AC-4: Step 1에서 좌표 없는 카드는 체크박스가 비활성 처리되고 저장 불가 안내 문구가 표시된다. → [FR-I3, BR-1]
- AC-5: Step 1에서 체크한 카드는 저장 후 tag=WISH, 미체크 카드(좌표 있는 것)는 tag=REEL로 저장된다. → [FR-I5, BR-2]
- AC-6: Step 2에서 메모를 입력하고 [저장]하면 저장된 모든 핀에 동일 메모가 기록된다. → [FR-I5, BR-3]
- AC-7: [건너뛰기]를 탭하면 메모 없이 저장이 실행된다. → [FR-I4]
- AC-8: 저장된 각 핀의 instagramUrl에 해당 릴스 URL이 기록된다. → [FR-I5]
- AC-9: 저장 완료 후 결과 버블이 채팅 뷰에 표시되며 "✨ 위시 N곳 · 📍 발견 M곳 저장했어요"와 신규 저장 성공 장소 이름 목록이 보인다. 409 중복 핀은 목록에 포함되지 않으며 N+M과 목록 건수가 일치한다. → [FR-I8]
- AC-10: 결과 버블의 [지도에서 보기 →] 버튼을 탭하면 지도 탭으로 전환된다. → [FR-I10, FR-I15, FR-I16]
- AC-11: 지도 탭 전환 후 해당 릴스 instagramUrl 핀만 표시되고 fitBounds로 모든 핀이 한 화면에 들어온다. → [FR-I11, FR-I12]
- AC-12: 지도 상단에 "🎬 이 릴스에서 저장한 N곳 · 전체 보기 ✕" 배너가 표시된다. → [FR-I13]
- AC-13: 배너 ✕를 탭하면 필터가 해제되고 배너가 닫힌다. → [FR-I13, BR-4]
- AC-14: 409(PLC_DUPLICATE_PIN) 응답은 에러로 처리하지 않고 흡수하며 결과 버블 목록에 포함되지 않는다. → [FR-I6, FR-I8]
- AC-15: sourceInstagramUrl이 없는 구버전 응답에서도 저장은 정상 동작하며 [지도에서 보기 →] 버튼은 표시되지 않는다. → [BR-7]

---

추가 확인 사항 없음. PRD가 확정되었습니다.
