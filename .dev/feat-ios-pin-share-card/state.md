phase: implement
status: in_progress
vcs-type: git
branch: feat/ios-pin-share-card
base: develop
dev-dir: .dev/feat-ios-pin-share-card
project-type: ios-swift (xcodegen) + next + spring (mixed repo)
project-root: ./
args: "iOS 공유 카드를 웹(frontend) 공유카드와 일치 — 배경/폰트 등"
flags: --hotfix (전체 이식)
mode: hotfix
intent-source: user-selection
started: 2026-06-09
current-step: "구현 완료 → 커밋/푸시(사용자 요청) → iOS CI 검증 대기"

## 핵심 발견
- iOS 앱에는 핀 공유 카드 기능이 **부재**(웹만 Phase 9 존재) → 신규 이식 작업.
- Mapbox SDK 미설치이나 MapConfig.accessToken 보유 → Static Images API 직접 호출로 지도 배경 생성.
- Windows 빌드 불가 → iOS CI(빌드+단위테스트) + Mac DoD-B 시각 검증.

## 구현 산출물(신규)
- ios/WhereWeGo/Features/Map/Share/MapboxStaticURL.swift
- ios/WhereWeGo/Features/Map/Share/ShareGeoToPixel.swift
- ios/WhereWeGo/Features/Map/Share/PinShareCard.swift (스펙/입력/글리프)
- ios/WhereWeGo/Features/Map/Share/PinShareCardView.swift (SwiftUI 카드)
- ios/WhereWeGo/Features/Map/Share/PinShareCardRenderer.swift (지도 fetch+흑백+글리프+blur+ImageRenderer)
- ios/WhereWeGo/Features/Map/Share/PinShareCardSheet.swift (미리보기+공유+사진저장)
- ios/WhereWeGoTests/{MapboxStaticURLTests,ShareGeoToPixelTests,PinShareCardSpecTests}.swift

## 수정
- ios/WhereWeGo/Features/Map/PinDetailContent.swift (헤더 공유 버튼 + .sheet)
- ios/project.yml, ios/WhereWeGo/Info.plist (NSPhotoLibraryAddUsageDescription)

## 남은 일(집에서 이어서)
- [ ] iOS CI(push 후) 빌드+단위테스트 green 확인
- [ ] Mac 시각 QA(DoD-B): 폰트 weight(Pretendard Regular+weight), 메모 lineSpacing, 태그 글리프 baseline 정렬, blur/베이지 톤, 글리프 형상(SF Symbol vs 웹 SVG) 미세 튜닝
- [ ] 공유 시트 실기기 동작(인스타/메시지/사진저장) 확인
- [ ] gx-dev complete(인수검증 + PR) — 또는 직접 PR
