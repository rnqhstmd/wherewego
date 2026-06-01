# Trust Ledger — P4 iOS 지도·핀·사진·방문감지

> phase-review 통합 감사 (qa-manager + security-auditor, 2026-06-02). AC-1~17 전부 코드 충족 확인. MUST-1/2/3 정합 확인.

## 통합 감사 (review)

심각도 요약: **Critical(QA) 1 / CRITICAL(ZT) 0 / HIGH 3 / MEDIUM 5 / Warning 3 / Info 2 / LOW 2**

### 즉시 수정 (이번 review에서 coder 수정)
- **[Critical/QA · MEDIUM-5/ZT] scenePhase 방문감지 생명주기 비대칭** — MapView `.onChange(scenePhase)`가 `.background`에서 `stopVisitDetection()` 미호출, `.active`에서 `startVisitDetection()` 미재개 → 백그라운드 복귀 후 방문감지 사일런트 중단(FR-27 위반).
  - 권고: `.background`에 `stopVisitDetection()`, `.active`에 `startVisitDetection()` 추가.
- **[HIGH-1/ZT] PlaceAPI 검색 쿼리 인젝션 경로** — `q`를 `.urlQueryAllowed`로 인코딩(=,&,+ 허용) 후 `percentEncodedQuery`에 주입 → `&injected=` 등 파라미터 인젝션 가능.
  - 권고: 값 전용 문자셋(`urlQueryAllowed.subtracting("=&+#")`) 또는 URLComponents queryItems로 안전 조립.
- **[HIGH-3/ZT] SquareCropView 제스처 취소 시 크롭 rect 오산** — `@GestureState` 드래그가 시스템 인터럽트로 취소되면 `onEnded` 미호출 → offset 미반영, 잘못된 영역 업로드 가능.
  - 권고: 취소 안전 상태 관리(onChanged 누적/명시적 commit).
- **[MEDIUM-1/ZT] 메모 ≤500자 API 계층 검증 누락** — UI(TextEditor)만 가드, `updateMemoOptimistic` 직접 호출 시 초과 전송 가능.
  - 권고: `updateMemoOptimistic`에 길이 검증 추가.
- **[MEDIUM-3/ZT] confirmVisit 실패 시 무한 토스트 루프 가능** — PATCH 실패 시 shownVisitPinIds 제거+clearFirstEnterAt → 조건 충족 시 즉시 재토스트 반복.
  - 권고: 실패 시 세션 내 재토스트 차단(shownVisitPinIds 유지) — 사용자는 PinDetail에서 수동 태그 변경 가능.
- **[Warning/QA] 클러스터 원 32px 스펙 불일치(현재 18px)** — MapboxMapView #if circleRadius. DoD-B 영역이나 코드 값 수정 가능.
- **[QUESTION/QA→수정] PinDetailViewModel `unowned mapViewModel`** — 두 감사 모두 방어적 `weak` 권장(View 계층상 즉시 위험은 낮음). 방어적으로 weak 전환.

### 수용된 리스크 / 이월 (수정 안 함)
- **[HIGH-2/ZT · Warning/QA] 204 NO_CONTENT 에러 경계** — 현재 `PinAPI.delete`가 NO_CONTENT를 흡수하여 **동작은 정확**. 명확성 개선(Void 반환 분리)은 후속 리팩터로 이월. APIClient 주석으로 의도 명시.
- **[Info/QA] VisitMemoSheet 빈 메모 skip** — 설계 의사결정(빈 메모는 PATCH 없이 건너뛰기). 웹과 동작 차이 미미, 수용.
- **[MEDIUM-4/ZT] reRoll 위치 실패 시 직전 결과 유지** — 안내 부재는 폴리시 후속. LOW 영향, 수용(직전 결과 유지).
- **[LOW-1/ZT] authorizedAlways 수용** — 백그라운드 추적 코드 없음. 포그라운드 전용 정책 유지, 실질 위험 없음. 수용.
- **[Info/QA] handleVisitSample 이중 shownPinIds 체크** — evaluate가 이미 필터링하므로 방어 코드. 주석 명시 후 유지.
- **[LOW-2/ZT] UpdatePinRequest memberwise init** — 확인됨, 조치 불필요.

### 시크릿/토큰
- MAPBOX_ACCESS_TOKEN/STYLE_URL은 placeholder("MAPBOX_TOKEN_NOT_SET" / standard style)로만 커밋. 실제 토큰·키 하드코딩 없음. KAKAO 등 기존 시크릿 노출 회귀 없음. ✅

### PR 본문 요약용
- P4는 token 없이 빌드·169 XCTest 통과(DoD-A). Mapbox 실렌더링은 token 발급 후 검증(DoD-B). 보안 CRITICAL 0건. review 지적 Critical/HIGH/주요 MEDIUM 수정 완료.
