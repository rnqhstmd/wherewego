## 코드 맵: iOS P6 — 디자인 정합성 최종 QA(웹↔앱) + 토큰 단일소스 가드

### 핵심 파일
- frontend/src/lib/design/tokens.ts → 웹 디자인 토큰 단일소스(colors 22키 + fonts 4키). drift 가드의 기준(source of truth)
- ios/WhereWeGo/Core/DesignSystem/Theme.swift → iOS 토큰 이식본(WGColor enum + WGFont). tokens.ts와 1:1 대조 대상
- frontend/src/app/globals.css → 웹 @theme 변수(폰트 var·spacing·기타 시각 토큰). 색/폰트 외 스펙 대조 레퍼런스
- ios/WhereWeGo/Features → iOS 화면 11개 도메인(Auth/Chat{Bot,Couple}/Group/Map/Onboarding/Photo/Pin/Place/Common). 스펙 정적 대조·보정 대상
- ios/WhereWeGo/Resources/Fonts → 커스텀 폰트 번들 위치(현재 비어있음 추정 — Theme.swift가 참조하는 PostScript명 대비 갭 가능성)

### 참조 파일
- ios/WhereWeGo/Info.plist → UIAppFonts 4종 등록(파일 없으나 등록 완료). Theme.swift PostScript명 정합 기준
- ios/WhereWeGo/Resources/Fonts/.gitkeep → 폰트 디렉토리 비어있음 확인(미번들 갭)
- frontend/src/app/login/LoginClient.tsx → 웹 로그인 레퍼런스. letterSpacing(-1.5)·lineHeight(1.05) 원본
- frontend/src/app/layout.tsx → next/font 4종 주입, PostScript명 매핑 기준
- frontend/src/app/map/_components/DesktopActionPill.tsx → cubic-bezier(0.2,0.8,0.2,1) 말풍선 팝 easing 원본
- frontend/src/app/map/_components/MapboxView.tsx → 핀 드롭 cubic-bezier(0.2,0.8,0.2,1) easing 원본
- frontend/src/app/map/_components/PinShareSheet.tsx → cubic-bezier(0.16,1,0.3,1) 시트 슬라이드 easing
- ios .../Features/Auth/LoginView.swift:34-49 → 워드마크/태그라인 보정 실증 대상(tracking/lineSpacing 미적용)
- ios .../Features/Map/MapView.swift:356, Chat/{ChatMessageRow:115,ChatScrollContainer:136}.swift → iOS easing 적용처(.easeOut/.easeInOut) — timingCurve 대응 후보
- ios .../Features/Onboarding/{NicknameView:22,GroupStartView:77,InviteCodeView,WelcomeWizardView}.swift, Group/GroupCreateView.swift, Common/PermissionDialogView:42 → 헤드라인 letterSpacing/lineSpacing 보정·재대조 대상
- frontend/src/app/globals.css:171-196 → maygo-preview-pin-drop/maygo-bubble-pop keyframe(cubic-bezier 원본)
- frontend/src/app/map/MapClient.tsx → 웹 지도 화면 레퍼런스(iOS Map 대조)
- frontend/src/app/map/_components/PinPopup.tsx → 핀 팝업 레퍼런스(iOS Pin 대조)

### 설정
- .claude/config.json → 프로젝트 타입(java-spring/node), 빌드 명령. iOS는 별도(XcodeGen)
- ios/project.yml → XcodeGen 프로젝트 정의(추정, 폰트/리소스 번들 구성 확인용)

### 비고
- 환경 제약: iOS 시각 픽셀 QA·TestFlight·앱스토어 제출은 Mac/시뮬레이터 필요 → 이번 실행 범위에서 제외(Mac 잔여 체크리스트로 문서화).
- 이번 실행 범위: (1) tokens.ts↔Theme.swift 토큰 drift 가드 구축, (2) SwiftUI 화면 스펙(tracking/lineSpacing/easing/레이아웃·폰트·색) 정적 대조·보정, (3) Mac 전용 잔여 산출물 문서화.
