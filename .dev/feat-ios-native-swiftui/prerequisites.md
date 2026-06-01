# 외부 발급·설정 체크리스트 (사용자 직접 작업)

> Claude가 코드는 작성하지만 **Apple/Kakao/Mapbox 콘솔에서의 키 발급·앱 등록·결제는 사람이 직접** 해야 한다.
> 각 항목에 **어디서 / 무엇을 / 어디에 넣는지 / 어느 Phase에 필요한지**를 적었다. 리드타임 긴 것(★)은 **지금 바로** 시작.
> Claude는 각 Phase 착수 직전 "이번 Phase에 필요한 발급물"을 다시 안내한다(아래 §7).

---

## 0. 선결 — 가장 먼저 (리드타임 ★)

| 항목 | 내용 | 비고 |
|---|---|---|
| **Mac + Xcode** | iOS 빌드·제출은 **macOS + Xcode 에서만** 가능 | ⚠️ 현재 개발 PC가 Windows → **별도 Mac 필요**(실기/맥미니/클라우드 Mac). 없으면 P3부터 막힘 |
| **Apple Developer Program 가입** | $99/년. 푸시·Sign in with Apple·앱 제출 전부의 전제 | 심사 수 시간~하루 소요 → **지금 신청**. 가입 후 **Team ID** 확보 |
| **Bundle ID 결정** | 예: `com.wherewego.app` | P1에서 Apple 로그인 `aud` 검증에 쓰임 → 먼저 확정 |

---

## 1. Apple — 식별자 / 키 (Apple Developer)

| 항목 | 어디서 | 결과물 | 넣는 곳 | 필요 Phase |
|---|---|---|---|---|
| App ID(Bundle ID) 등록 | Developer ▸ Identifiers | `com.wherewego.app` | Xcode 서명 / 백엔드 `aud` | P1 |
| Sign in with Apple 활성화 | 위 App ID ▸ Capabilities 체크 | — | App ID 설정 | P3(앱), P1(aud용 ID) |
| **Sign in with Apple Key(.p8)** | Developer ▸ Keys ▸ 새 키 + "Sign in with Apple" 체크 | `AuthKey_XXXX.p8` + **Key ID** | 백엔드(계정 삭제 시 토큰 **revoke** client_secret 서명) | P2 |
| **APNs Auth Key(.p8)** | Developer ▸ Keys ▸ 새 키 + "Apple Push Notifications service(APNs)" 체크 | `AuthKey_YYYY.p8` + **Key ID** | 백엔드(푸시 발송) | P2 |
| Push Notifications capability | App ID ▸ Capabilities | — | Xcode entitlement | P5 |
| Associated Domains | App ID ▸ Capabilities + 웹 도메인에 **AASA 파일**(`apple-app-site-association`) 호스팅 | — | Xcode entitlement `applinks:<도메인>` | P5(Universal Links / 초대·딥링크) |
| 개발/배포 인증서·프로비저닝 | Xcode **자동 서명** 권장 | — | Xcode | P3~ |

> ⚠️ `.p8` 키는 **다운로드 1회만** 가능 — 분실 시 재발급해야 함. 안전 보관.
> 토큰 기반(.p8) APNs는 인증서와 달리 **만료가 없어** 권장.

---

## 2. Kakao (Kakao Developers)

> 웹에서 이미 Kakao 로그인을 쓰므로 **앱이 이미 존재할 가능성 높음** → 같은 앱에 **iOS 플랫폼만 추가**.

| 항목 | 어디서 | 결과물 | 넣는 곳 | 필요 Phase |
|---|---|---|---|---|
| iOS 플랫폼 등록 | 내 앱 ▸ 플랫폼 ▸ iOS ▸ Bundle ID 입력 | — | — | P3 |
| **Native App Key** | 내 앱 ▸ 앱 키 | `Native app key` | xcconfig / Info.plist(SDK init) | P3 |
| Custom URL Scheme | Info.plist | `kakao{NATIVE_APP_KEY}` + `LSApplicationQueriesSchemes`(`kakaokompassauth`,`kakaolink`) | Info.plist | P3 |
| Kakao Login + 동의항목 | 내 앱 ▸ 카카오 로그인 ▸ 활성화/동의항목(닉네임·이메일) | — | — | P1 확인 / P3 사용 |
| REST(또는 Admin) Key | 내 앱 ▸ 앱 키 | 서버측 토큰 검증용 | 백엔드 | P1 (이미 웹에서 사용 중일 것 → 확인만) |

---

## 3. Mapbox

> 웹에서 mapbox-gl-js 사용 중 → 계정/스타일 재사용. **iOS SDK 다운로드용 secret 토큰만 신규.**

| 항목 | 어디서 | 결과물 | 넣는 곳 | 필요 Phase |
|---|---|---|---|---|
| Public access token | Mapbox ▸ Account ▸ Tokens | `pk.xxx` | xcconfig(런타임 지도) | P4 (P3에 미리) |
| **Secret download token** | Mapbox ▸ Tokens ▸ 새 토큰 + **`Downloads:Read`** 스코프 | `sk.xxx` | `~/.netrc`(SPM이 Mapbox SDK 다운로드) | P3(SDK 추가 시) |
| Style URL | 웹 커스텀 스타일 재사용 | `mapbox://styles/...` | xcconfig | P4 |
| MAU 과금 한도 | Mapbox 요금 페이지 | — | — | ⚠️ 사전 확인(무료 ~25k MAU 수준) |

---

## 4. 백엔드에 주입할 시크릿 (사용자 발급 → Claude가 배선)

발급되는 값을 알려주면 EC2 환경변수(또는 시크릿 매니저)에 넣고 코드 배선은 Claude가 진행.

- **푸시**: `APNS_AUTH_KEY`(.p8 내용) · `APNS_KEY_ID` · `APPLE_TEAM_ID` · `APP_BUNDLE_ID`(=APNs topic)
- **Apple 로그인/revoke**: `APPLE_SIGNIN_KEY`(.p8) · `APPLE_SIGNIN_KEY_ID` · `APPLE_TEAM_ID` · `APPLE_CLIENT_ID`(=Bundle ID)
- **Kakao**: REST/Admin Key (기존 웹 설정 재확인)

> Apple 로그인 **검증**은 Apple 공개 JWKS(시크릿 불필요)로 충분하지만, **계정 삭제 시 토큰 revoke**에는 위 Sign-in `.p8`로 client_secret을 서명해야 한다 → P2에 필요.

---

## 5. 에셋 / 기타

| 항목 | 내용 | 필요 Phase |
|---|---|---|
| 폰트 파일 | Noto Serif KR · Gowun Batang · Pretendard · JetBrains Mono (전부 무료/오픈 라이선스) — 공식 배포처 다운로드 → `Resources/Fonts` + Info.plist `UIAppFonts` | P3 |
| 앱 아이콘 / 스플래시 | 디자인 에셋 → Assets 카탈로그 | P5 |
| 개인정보처리방침 URL | 웹에 페이지 게시 → App Store Connect 입력 | P5/P6 |
| 리뷰어 데모 계정 | 시드 데이터(커플 그룹+샘플 핀/채팅) + 안내문 → App Store Connect ▸ App Review Information | P5 |
| App Store Connect 앱 레코드 | 앱 생성, 메타데이터, 스크린샷, App Privacy 라벨 | P5/P6 |

---

## 6. 권한 문구 (Info.plist — 발급 아니라 작성, P4/P5)
- `NSLocationWhenInUseUsageDescription` (방문 감지 — 포그라운드)
- `NSCameraUsageDescription` · `NSPhotoLibraryUsageDescription` (사진)
- 푸시 동의는 런타임 prompt(문구 별도 불필요)

---

## 7. 리드타임 순 — "지금 당장" vs "Phase 착수 직전"

**지금 바로 (리드타임 길어서 미리):**
1. **Apple Developer Program 가입** (심사 지연 가능)
2. **Mac + Xcode 확보** (P3 전까지)
3. **Bundle ID 확정** (P1 전)

**Phase 착수 직전에 발급 (Claude가 그때 다시 안내):**

| Phase | 그때 발급/준비할 것 |
|---|---|
| **P1** | Bundle ID 등록, Apple Team ID, (Kakao REST key 확인) |
| **P2** | APNs `.p8`+Key ID, Apple Sign-in `.p8`+Key ID, Team ID → 백엔드 주입 |
| **P3** | Mac+Xcode, Kakao iOS 플랫폼+Native key+URL scheme, Apple "Sign in with Apple" capability, Mapbox secret 토큰(.netrc), 폰트 파일 |
| **P4** | Mapbox public 토큰 + style URL |
| **P5** | Push capability, Associated Domains + AASA 호스팅, App Store Connect 앱 레코드, 데모 계정 시드, 아이콘 |
| **P6** | 스크린샷, App Privacy 라벨, 개인정보처리방침 URL, 제출 |

> Claude는 각 Phase를 시작할 때 위 목록 중 **해당 항목을 콘솔 경로와 함께 다시 띄워** 바로 발급할 수 있게 안내한다.
