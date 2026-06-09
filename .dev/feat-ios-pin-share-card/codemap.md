## 코드 맵: iOS 핀 공유 카드 (웹 Phase 9 이식)

### 핵심 파일 (신규)
- ios/WhereWeGo/Features/Map/Share/PinShareCard.swift → 카드 스펙 상수 + 입력 모델 + 태그색/글리프 헬퍼
- ios/WhereWeGo/Features/Map/Share/MapboxStaticURL.swift → Static Images API URL 빌더(순수, 웹 mapboxStaticUrl.ts 이식)
- ios/WhereWeGo/Features/Map/Share/ShareGeoToPixel.swift → Web Mercator 투영(순수, 웹 geoToPixel.ts 이식)
- ios/WhereWeGo/Features/Map/Share/PinShareCardView.swift → 1080×1350 SwiftUI 카드 뷰(텍스트/워터마크)
- ios/WhereWeGo/Features/Map/Share/PinShareCardRenderer.swift → 지도 fetch+흑백+글리프+blur(CG/CoreImage) → ImageRenderer 합성
- ios/WhereWeGo/Features/Map/Share/PinShareCardSheet.swift → 공유 시트(미리보기+ShareLink+사진저장)

### 참조 파일 (수정/연동)
- ios/WhereWeGo/Features/Map/PinDetailContent.swift → 헤더에 공유 버튼 + .sheet(공유 카드) 추가
- ios/WhereWeGo/Features/Map/MapViewModel.swift → pins(그룹 핀 단일 출처) 제공
- ios/WhereWeGo/Core/Config/MapConfig.swift → accessToken / styleURL 제공
- ios/WhereWeGo/Core/DesignSystem/Theme.swift → WGColor/WGFont(ink #1A1A2E, pinReel/Wish/Memory, Pretendard)
- ios/WhereWeGo/Features/Pin/PinAPI.swift → PinSummary(placeName/address/memo/tag/createdAt/createdByNickname/lat/lng)
- ios/WhereWeGo/Features/Map/VisitToastView.swift → VisitDateFormatter(createdAt → YYYY.MM.DD) 재사용

### 테스트 (신규)
- ios/WhereWeGoTests/MapboxStaticURLTests.swift
- ios/WhereWeGoTests/ShareGeoToPixelTests.swift

### 설정
- ios/project.yml → sources 폴더 재귀 포함(신규 파일 자동) / 공유 시트 사진저장 시 NSPhotoLibraryAddUsageDescription 필요 여부 점검
- 웹 원본: frontend/src/lib/share/renderPinCard.ts, mapboxStaticUrl.ts, geoToPixel.ts, app/map/_components/PinShareSheet.tsx, lib/pin/markers.tsx
