# PRD: P4 — iOS 지도·핀·사진·방문감지

> 브랜치 `feat/ios-native-p4-map-pin-photo` (base: develop). WhereWeGo 웹→SwiftUI 전환 4단계. 의존: P1(백엔드 인증)·P3(iOS 골격/인증/온보딩) 완료.

## 확정 결정 (Q&A, 2026-06-01)

- **Q1 Mapbox SDK**: **배선 우선, 토큰 나중 (P3 패턴)**. `#if canImport(MapboxMaps)` 조건 컴파일 + 플레이스홀더로 모든 지도 로직 작성. project.yml SPM 추가/실컴파일은 secret download token(.netrc) 발급 후. XCTest는 지금도 통과해야 함.
- **Q2 방문 감지**: **P4 Must 포함**. roadmap 원안대로 지도·핀·사진과 함께 P4에서 완성.
- **Q3 사진 크롭**: **SwiftUI 자작 1:1 크롭** (roadmap.md "크롭 뷰 자작" 명시). PHPicker 내장 크롭 API는 존재하지 않음 → 자작 정석.

---

## 배경

WhereWeGo 웹→SwiftUI 네이티브 전환의 네 번째 단계. P1(백엔드 인증 확장)·P3(iOS 골격·인증·온보딩) 완료 위에 앱의 핵심 가치인 **커플 공유 지도 경험**을 SwiftUI로 옮긴다. 현재 iOS는 로그인→온보딩→그룹 진입까지만 동작하며 지도 화면이 없다. 웹 `MapClient.tsx`(Mapbox GL JS)가 지도 렌더·핀 CRUD·태그/메모·룰렛·방문감지(포그라운드)를 모두 구현하고 있고, P4는 이를 Mapbox iOS SDK 기반 SwiftUI로 포팅한다. 6 Phase 중 **최대 공수** 단계이며 임계 경로(P4→P5→P6→제출)의 핵심.

현재 제품 상태:
- 백엔드 API 운영 중: `POST/GET/PATCH/DELETE /api/v1/groups/{groupId}/pins`, `POST/DELETE .../photo`, `GET /api/v1/places/search?q=`
- 핀 태그: REEL(하늘색 원)/WISH(노랑 별)/MEMORY(핑크 하트)
- 사진: MEMORY 핀 1장(JPEG/PNG/WebP, ≤2MB, 장변 ≤4096px), S3 원본+WebP 썸네일
- 방문 감지: WISH/REEL 100m·30초 → MEMORY 전환(포그라운드)
- iOS project.yml: Mapbox SDK 미추가(Kakao SDK만), `NSLocationWhenInUseUsageDescription`은 Info.plist에 이미 존재
- Mapbox public token·style URL·secret download token: 사용자 직접 발급물

## 목표

- 커플이 iOS 앱에서 공유 지도를 열고 핀을 저장·편집·삭제하며 방문 장소를 MEMORY로 기록.
- 성공 지표: 시뮬레이터(iOS 17+)에서 Mapbox token 없이 빌드·XCTest 통과 / token 보유 후 실기기 E2E(지도 렌더·핀 CRUD·방문감지) 동작.

## 요구사항

### 지도 렌더링
- **[Must] P4-FR-1** Mapbox SDK를 `#if canImport(MapboxMaps)` 분기로 배선. project.yml SPM 추가는 token 발급 후. xcconfig `MAPBOX_ACCESS_TOKEN`·`MAPBOX_STYLE_URL` 키 추가. SDK/token 부재 시 플레이스홀더 뷰로 빌드 성공.
- **[Must] P4-FR-2** 그룹 진입 후 MapView 표시. 초기 카메라: 위치 granted면 현재위치(zoom 15) flyTo, 미허용 시 서울시청(37.5,127.0, zoom 3) 기본.
- **[Must] P4-FR-3** 그룹 핀 초기 일괄 로드(`GET /api/v1/groups/{groupId}/pins`, 페이지 파라미터 미전달=legacy 모드 `{items}`) → 마커.
- **[Must] P4-FR-4** 태그별 마커 구분: REEL=하늘색 원(22px), WISH=노랑 별(18px), MEMORY=핑크 하트(22px). 웹 `renderPinDotInto()` 재현.
- **[Should] P4-FR-5** 클러스터(줌아웃 시 rust 32px 원+숫자, 탭 시 fitBounds 확대). 웹 supercluster 대응.
- **[Must] P4-FR-6** 태그 필터(REEL/WISH/MEMORY 독립 토글, 기본 전체 표시, 변경 즉시 반영).
- **[Must] P4-FR-7** 핀 0개 빈 상태 화면(웹 EmptyMapCard 대응).

### 핀 상세(정보창)
- **[Must] P4-FR-8** 마커 탭 → 정보창(하단 시트/팝업): 장소명·주소·메모(있을 때)·IG URL 바로가기(https만)·태그·등록자 닉네임.
- **[Must] P4-FR-9** 태그 변경(REEL↔WISH↔MEMORY): `PATCH .../pins/{pinId}` `{tag}`. 낙관적 마커 반영, 실패 시 롤백+인라인 에러.
- **[Must] P4-FR-10** 메모 편집: `PATCH {memo}`. ≤500자. 성공 즉시 반영, 실패 시 입력 보존+에러.
- **[Should] P4-FR-11** 장소명 편집: `PATCH {placeName}`. ≤200자, 빈 값 불가.
- **[Must] P4-FR-12** 핀 삭제: 확인 다이얼로그 → `DELETE .../pins/{pinId}`(204). 낙관적 제거, 실패 시 복원+에러.

### 검색 → 핀 추가
- **[Must] P4-FR-13** 검색: `GET /api/v1/places/search?q=` → `items[]{placeName,address,latitude,longitude}` 목록.
- **[Must] P4-FR-14** 결과 선택 → 태그 선택 → `POST .../pins` `{placeName,address,latitude,longitude,tag,memo?,instagramUrl?}` → 마커 추가 + flyTo(zoom 15).
- **[Should] P4-FR-15** 크로스헤어(+) 임의 좌표 핀 추가(중앙 좌표, 소수점 7자리 반올림 → 태그 선택 → 저장).

### 사진(MEMORY 핀)
- **[Must] P4-FR-16** MEMORY 정보창에서 PHPicker 사진 선택(`NSPhotoLibraryUsageDescription`) → `POST .../pins/{pinId}/photo` multipart `file`.
- **[Must] P4-FR-17** **1:1 크롭 뷰(SwiftUI 자작)** → 장변 1600px JPEG 압축(2MB 초과 시 품질 단계 감소). 업로드 `image/jpeg`.
- **[Must] P4-FR-18** 사진 삭제: `DELETE .../pins/{pinId}/photo`(확인 다이얼로그).
- **[Must] P4-FR-19** `NSCameraUsageDescription`·`NSPhotoLibraryUsageDescription` Info.plist(project.yml) 추가.

### 룰렛
- **[Must] P4-FR-20** 현재 위치 기준 WISH/REEL 랜덤 1개 추첨(권한 미허용 시 권한 요청 선행). 결과: 장소명·거리(km)·태그.
- **[Must] P4-FR-21** "지도에서 보기" → flyTo(zoom 15) + 정보창 자동.
- **[Must] P4-FR-22** "다시" 재추첨 + MEMORY 포함 토글(기본 OFF).
- **[Must] P4-FR-23** 후보 0개 빈 상태("추첨할 핀이 없어요").
- **[Should] P4-FR-24** 핀 목록 5분 캐시, 룰렛 직전 stale 시 재조회.

### 카메라
- **[Must] P4-FR-25** flyTo(700ms, zoom 15) — 핀추가/룰렛 보기/방문확인.
- **[Should] P4-FR-26** fitBounds(padding 80, maxZoom 15, 700ms) — 클러스터 확장/번들 진입.

### 방문 감지(포그라운드)
- **[Must] P4-FR-27** 포그라운드 CoreLocation, WISH·REEL 핀 반경 100m·30초 체류 → "방문 확인" 토스트.
- **[Must] P4-FR-28** 게이트: GPS 정확도 ≤50m만 평가(초과 시 평가 스킵·타이머 보존), 속도 >1.4m/s 시 모든 후보 타이머 초기화, Haversine 거리, 동시 100m 내 복수 핀은 최근접 1개만 토스트.
- **[Must] P4-FR-29** "네, 다녀왔어요" → `PATCH {tag:MEMORY}`. 응답 `transitionedToMemoryNow=true`일 때만 confetti(하트 3개 600ms)+메모 시트, `false`(동시 전환 충돌)면 안내 토스트만.
- **[Must] P4-FR-30** 방문 메모 시트: 헤더 "다녀온 흔적", 날짜("다녀온 날·YYYY.MM.DD", `visitedAt`), 자유 텍스트 → `PATCH {memo}`. 저장/건너뛰기 후 정보창 자동 오픈.
- **[Must] P4-FR-31** 세션 단위 중복 감지 방지(토스트 노출 핀은 앱 재시작 전 재감지 안 함).
- **[Should] P4-FR-32** granted 상태에서 5초 폴링 보완(didUpdateLocations 희소성 보완).

### 비즈니스 규칙
- **[Must] P4-BR-1** 모든 API는 `APIClient`(Bearer 자동·401 refresh 재시도) 경유, `GroupAPI.swift` 패턴 준수.
- **[Must] P4-BR-2** 핀 CRUD 권한=그룹 활성 멤버 전체(등록자 무관). 403 `GROUP_NOT_MEMBER` 시 "권한이 없어요".
- **[Must] P4-BR-3** Instagram URL은 `https://` 시작만 링크 표시(XSS 방어).
- **[Must] P4-BR-4** 핀 등록 시 태그 필수, 좌표 위도 -90~90/경도 -180~180, 장소명 ≤200자, 메모 ≤500자.
- **[Must] P4-BR-5** 사진은 MEMORY 핀만, JPEG/PNG/WebP ≤2MB 장변 ≤4096px, 초과 시 클라이언트 압축.
- **[Must] P4-BR-6** 방문 감지는 WISH·REEL만(MEMORY 제외).
- **[Should] P4-BR-7** 핀 폴링 append-only(타 사용자 신규 핀 동기화). 기존 핀 수정·삭제는 로컬 낙관적만.

### 품질 기대
- **[Should] P4-QE-1** Mapbox token 없이 `xcodebuild test` 빌드 성공·XCTest 통과(지도 뷰 플레이스홀더).
- **[Should] P4-QE-2** 네트워크 오류 시 "다시 시도" 가능한 에러 UI.
- **[Should] P4-QE-3** 사진 업로드 중 로딩 인디케이터, 실패 시 인라인 에러.

## 수용 기준

- **AC-1** MAPBOX_ACCESS_TOKEN 미설정에서 `xcodebuild build` 성공(플레이스홀더 분기). [FR-1, QE-1]
- **AC-2** `xcodebuild test`에서 PinAPI/PlaceAPI 단위테스트(mock 응답) 전체 통과. [FR-3, FR-13, BR-1]
- **AC-3** PinSummary DTO가 백엔드 응답 필드 전부 포함(`id,groupId,createdBy,createdByNickname,placeName,address,latitude,longitude,instagramUrl,memo,memoSource,tag,createdAt,visitedAt,memoUpdatedBy,memoUpdatedByNickname,photoUrl,photoThumbnailUrl`). [FR-3] *(실제 DTO Read로 필드 확정)*
- **AC-4** 태그 필터 OFF 시 해당 마커 제거, ON 시 복귀. [FR-6]
- **AC-5** 핀 0개 빈 상태 뷰 표시. [FR-7]
- **AC-6** PATCH tag=MEMORY 낙관적 핑크 하트 반영, 서버 실패 시 원래 태그 복원. [FR-9]
- **AC-7** 삭제 다이얼로그 "취소" 시 유지, "삭제" 시 즉시 제거+DELETE. [FR-12]
- **AC-8** 1:1 크롭 → JPEG 변환 → multipart POST, 성공 시 photoUrl/photoThumbnailUrl 갱신. [FR-16, FR-17]
- **AC-9** 비-MEMORY 핀 정보창에 사진 업로드 버튼 미표시. [BR-5]
- **AC-10** 룰렛 WISH/REEL 0개 시 "추첨할 핀이 없어요". [FR-23]
- **AC-11** 룰렛 "지도에서 보기" 시 flyTo(zoom 15) 호출. [FR-21]
- **AC-12** 방문감지 단위테스트: accuracy=60m 이벤트로 firstEnterAt 타이머 미초기화. [FR-28]
- **AC-13** 방문감지 단위테스트: speed=2.0m/s 시 모든 firstEnterAt 초기화. [FR-28]
- **AC-14** 방문감지 단위테스트: 100m 내 30초 경과 시 detectedPinId 반환, 세션 중복 차단. [FR-27, FR-31]
- **AC-15** `transitionedToMemoryNow=false` 시 confetti 미발사·메모시트 미오픈. [FR-29]
- **AC-16** `NSCameraUsageDescription`·`NSPhotoLibraryUsageDescription`이 project.yml에 존재. [FR-19]
- **AC-17** IG URL이 https:// 아니면 링크 미노출. [BR-3]

## 제외 범위

- **P5 이월**: 핀 저장 시 파트너 APNs 푸시 수신, 방문감지 VISIT_DETECTED 알림함(백엔드 fan-out은 완성, iOS UI는 P5), 채팅 연동.
- **P6 이월**: 웹↔앱 디자인 픽셀 정합성 대조·보정, 디자인 토큰 drift 체크.
- **후속(미정)**: 핀 좌표 수정(PinCoordinateEditPicker), 백그라운드 위치추적, `/pins` 별도 목록 화면, WANT/`want_count` 시스템(Phase 12), 알림 딥링크 reel_bundle dim 처리(P5와 함께).
- **이번 미포함**: Instagram URL 직접 입력 UI(검색→등록에서 생략, PATCH로 추가 가능).

## 엣지케이스

| 상황 | 처리 |
|------|------|
| Mapbox SDK 미추가(token 미발급) | `#if canImport(MapboxMaps)` 미충족 → 플레이스홀더 뷰. 빌드·XCTest 성공 |
| Mapbox public token 부재 | 플레이스홀더("지도를 불러올 수 없어요"). 빌드 성공 |
| 핀 0개 | "아직 저장한 핀이 없어요" 빈 상태, 검색/추가 액션 유지 |
| 네트워크 오류(핀 로드 실패) | 에러 뷰 + "다시 시도" |
| 위치 권한 거부 | 룰렛: 안내+설정 유도 / 방문감지: 비활성 |
| 위치 권한 미결정 | 룰렛 첫 실행 시 권한 요청, prompt 상태 자동 flyTo 미실행 |
| 사진 >2MB | 장변 1600px JPEG 압축 후 재시도, 그래도 초과 시 "파일이 너무 커요" |
| 사진 형식 오류 | PHPicker `.images` 필터로 선택 제한 |
| 방문 토스트 중 다른 시트 | 동시 1패널 정책(웹 동일) |
| 방문감지 동시 복수 핀 | 최근접 1개 토스트, 나머지 타이머 병행 유지 |
| 방문 PATCH 1차 실패 | 인라인 에러 토스트(1.5초), 태그 미변경 유지 |
| 방문 메모 2차 PATCH 실패 | 메모 미저장, 1차 MEMORY 전환은 유지 |
| GPS 속도 >1.4m/s | 모든 후보 타이머 초기화(차량 추정) |
| GPS 정확도 >50m | 평가 스킵, 타이머 보존 |
| 백그라운드 복귀 | scenePhase 변화 → 모든 타이머 초기화(웹 visibilitychange 대응) |

## 미해결 가정/리스크

1. Mapbox SDK는 private SPM — secret download token(.netrc) 없으면 SPM resolve 실패. → 배선 우선 패턴으로 회피(Q1 확정).
2. Mapbox public token·style URL 미보유 → 지도 실동작 검증은 발급 후 이월.
3. 크롭 뷰 자작 공수(드래그·핀치 줌·1:1 고정).
4. CoreLocation 포그라운드 정밀도는 시뮬레이터 불가 → 실기기 검증은 token 보유 후.
5. 룰렛 반경 확장 알고리즘(`pickRandomWithExpansion`) 포팅 — 단위 테스트 커버 중요.
