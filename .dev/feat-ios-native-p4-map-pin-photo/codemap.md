## 코드 맵: P4 — iOS 지도·핀·사진·방문감지 (MapClient.tsx 포팅)

### 핵심 파일
- frontend/src/app/map/MapClient.tsx → 웹 지도 메인(포팅 원본): 핀 로드/마커, 검색→핀추가, 정보창(태그/메모/삭제), 룰렛, 낙관적 업데이트(useOptimistic patch/remove)
- frontend/src/app/map/_components/MapboxView.tsx → Mapbox GL 래퍼: styleURL·카메라(flyTo/fitBounds)·마커 렌더(태그별 색/하트)
- ios/WhereWeGo/Core/Networking/APIClient.swift → Bearer/refresh 401 재시도 API 클라이언트(PinAPI/PlaceAPI 추가 기반)
- ios/WhereWeGo/App/AppDependencies.swift → DI 컨테이너(MapService/PinService/LocationService 등록 지점)
- ios/project.yml → XcodeGen 매니페스트: **Mapbox iOS SDK SPM 추가 지점**(현재 KakaoOpenSDK만), Info.plist 권한·token 주입

### 참조 파일
- backend .../interfaces/api/pin/PinV1Controller.java → 핀 CRUD 계약: GET/PATCH/DELETE /api/v1/groups/{groupId}/pins (legacy {items} vs paged), 사진 multipart POST/DELETE .../photo
- backend .../interfaces/api/place/PlaceV1Controller.java → 장소 검색 계약: GET /api/v1/places/search (Kakao/Google)
- ios/WhereWeGo/Features/Group/GroupAPI.swift → 도메인 API 작성 패턴(PinAPI 참조), 그룹 진입 후 지도
- ios/WhereWeGo/Core/Auth/SessionStore.swift → 세션/activeGroup 상태(지도 핀 조회용 groupId 출처)
- ios/WhereWeGo/App/RootView.swift / OnboardingRouter.swift → 온보딩 완료 후 지도(MapView) 진입 라우팅
- ios/WhereWeGo/Core/DesignSystem/Theme.swift → 디자인 토큰(마커 색 REEL/WISH/MEMORY, 정보창 AMOU 스타일)
- context/pin/architecture.md → 핀 도메인 계약(테이블/태그 REEL·WISH·MEMORY/사진 S3/instagram https 검증/방문감지 Phase10)
- context/map/architecture.md → 지도 도메인 계약(마커 표현/정보창/검색 UX/낙관적 업데이트)

### 포팅 원본 추가 (웹 로직)
- frontend/src/app/map/_lib/roulette.ts → pickRandomWithExpansion 반경 확장 추첨 알고리즘(iOS 포팅 대상)
- frontend/src/app/map/_hooks/useVisitDetection.ts → 방문 감지 평가(BBox prefilter + Haversine + 속도/정확도 게이트, iOS 포팅 대상)

### 계약 DTO 추가
- backend .../api/pin/PinV1Dto.java:180~244 → UpdatePinRequest JsonNode 부분수정(키부재/null/빈문자열 구분) — Swift 인코딩 근거
- backend .../api/pin/PinV1Controller.java:42~125 → page/size 미전달=legacy {items}; :173~204 multipart file·허용 contentType·매직바이트
- backend .../api/place/PlaceV1Dto.java → PlaceItem 응답 구조(placeName/address/lat/lng)
- context/pin/phase-10-visit-detection.md → 방문감지 트리거 사양(100m·30초·정확도·속도 게이트)

### 웹 포팅 포인트(라인)
- frontend/src/app/map/MapClient.tsx:1382~1419 → 방문 PATCH·transitioned 분기·confetti·메모시트 오케스트레이션
- frontend/src/app/map/MapClient.tsx:2255~2264 → computeTagsAllowed 룰렛 태그 교집합
- frontend/src/app/map/MapClient.tsx:781~782 → 크로스헤어 좌표 7자리 반올림
- ios LocationPermView.swift → CLLocationManager 권한 패턴(룰렛/방문 재사용); OnboardingFlags.swift → store 주입 패턴

### 구현 참조 (설계 2차 — 비판 반영)
- ios WhereWeGoApp.swift:2-15 → Kakao 무조건 import+런타임 게이트(P3 패턴, Mapbox #if canImport와 다름 MUST-1)
- ios APIClient.swift:42-85 → 401 재시도가 request/send 내부 고정·Content-Type application/json(:61) → performAuthorized 추출 근거 MUST-3
- ios KeychainTokenStore.swift:54-99 → refresh() inFlight 직렬화(multipart 401 재시도 정합)
- ios StubURLProtocol.swift + ActiveGroupDecodingTests.swift → API/401 시퀀스 테스트 템플릿(PinAPITests multipart 401)
- ios Debug.xcconfig:4 → ://→/$()/ 이스케이프(MAPBOX_STYLE_URL standard fallback)

### 설정
- ios/Config/Shared.xcconfig → API_BASE_URL/KAKAO key (MAPBOX_ACCESS_TOKEN / MAPBOX_STYLE_URL 추가 지점)
- ios/WhereWeGo/Info.plist → 권한 문구: NSLocationWhenInUseUsageDescription(존재) + NSCameraUsageDescription·NSPhotoLibraryUsageDescription(추가)
- .dev/feat-ios-native-swiftui/roadmap.md / prerequisites.md → P4 범위(지도·핀·사진·룰렛·방문감지) + 발급물(Mapbox public token·style URL, secret download token=.netrc)
