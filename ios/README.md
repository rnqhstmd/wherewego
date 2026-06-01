# WhereWeGo iOS (SwiftUI)

WhereWeGo 의 iOS 네이티브 앱. 앱스토어 전용. 백엔드(`../backend`)를 Bearer 토큰으로 직접 호출한다.
전환 설계·로드맵: `../.dev/feat-ios-native-swiftui/`.

## 디렉토리 구조

```
ios/
├── .gitignore
├── README.md                      ← 이 파일
└── WhereWeGo/
    ├── App/                       @main 진입점, 루트 라우팅
    ├── Core/
    │   ├── DesignSystem/Theme.swift   웹 design tokens 1:1 이식 (완료)
    │   └── Networking/APIClient.swift Bearer + 401 refresh (스켈레톤)
    ├── Features/                  Auth · Onboarding · Map · Pins · Chat · Settings · Group
    └── Resources/
        └── Fonts/                 Noto Serif KR · Gowun Batang · Pretendard · JetBrains Mono
```

`.xcodeproj` 는 아직 없다 — 아래 절차로 생성한다.

## Xcode 프로젝트 생성

1. Xcode → File → New → Project → iOS App
   - Product Name: `WhereWeGo`, Interface: SwiftUI, Language: Swift
   - 생성 위치를 `ios/` 로 지정해 위 `WhereWeGo/` 구조에 합류시킨다.
2. 기존 스캐폴드 파일(`Theme.swift`, `APIClient.swift`)을 타깃에 추가.
3. (선택) `.xcodeproj` 병합 충돌이 싫으면 **XcodeGen** 또는 **Tuist** 로 프로젝트를 생성·관리.

## 의존성 (Swift Package Manager)

Xcode → Add Package Dependency:
- Mapbox Maps: `https://github.com/mapbox/mapbox-maps-ios` — 지도 (mapbox-gl-js 대체, 동일 style URL 재사용)
- Kakao SDK: `https://github.com/kakao/kakao-ios-sdk` — 네이티브 로그인

> Mapbox 는 다운로드용 secret token(`.netrc`) 설정 필요. APNs 푸시는 Firebase 미사용 시 순정 APNs.

## 폰트

웹과 동일 폰트를 `Resources/Fonts/` 에 넣고 `Info.plist > UIAppFonts` 에 등록 → `Theme.swift` 의 PostScript 명을 실제 파일 기준으로 보정.

## 환경 / 빌드 설정

- `API_BASE_URL` 을 xcconfig(Debug=로컬, Release=운영)로 분리해 `APIClient(baseURL:)` 에 주입.
- 권한 문구(Info.plist): 위치(`NSLocationWhenInUseUsageDescription`), 카메라/사진(`NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription`), 푸시.
- entitlements: Push Notifications, Associated Domains(Universal Links: 초대/딥링크).

## 배포 영향 없음

루트 `.github/workflows/deploy.yml` 은 `paths: ['backend/**']` 필터라 `ios/` 변경은 백엔드 배포를 트리거하지 않는다.
