=== ios/WhereWeGo/Core/Validation/Nickname.swift:36 [gemini-code-assist[bot]] ===
![high](https://www.gstatic.com/codereviewagent/high-priority.svg)

### ⚠️ 한글 IME 입력(자음/모음 조합) 깨짐 버그

현재 `Nickname.sanitize` 메서드는 정규식 `^[가-힣a-zA-Z0-9]+$`를 사용하여 입력값을 필터링하고 있습니다. 하지만 `가-힣` 범위는 완성형 한글 음절만 포함하며, 한글 키보드로 입력 중인 **초성/중성 자모음(`ㄱ-ㅎ`, `ㅏ-ㅣ`)**은 포함하지 않습니다.

이로 인해 사용자가 한글로 닉네임을 입력하려고 할 때, 글자가 완성되기 전의 자모음(예: `ㄱ`)이 실시간으로 즉시 제거되어 **한글 입력이 완전히 불가능해지는 심각한 버그**가 발생합니다.

**해결 방안:**
실시간으로 텍스트를 정제하는 `sanitize` 단계에서는 한글 자모음(`ㄱ-ㅎㅏ-ㅣ`) 입력을 허용하고, 최종 제출 시 검증하는 `validate` 단계에서만 완성형 한글(`가-힣`)을 강제하도록 분리해야 합니다.

```swift
    /// 허용외 문자 제거 + 12자 절단(Character 단위).
    static func sanitize(_ value: String) -> String {
        let filtered = value.filter { ch in
            String(ch).range(of: "^[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]+$", options: .regularExpression) != nil
        }
        return String(filtered.prefix(12))
    }
```

=== ios/WhereWeGo/Core/Keychain/KeychainTokenStore.swift:88 [gemini-code-assist[bot]] ===
![security-high](https://www.gstatic.com/codereviewagent/security-high-priority.svg) ![high](https://www.gstatic.com/codereviewagent/high-priority.svg)

### 🔒 액터 재진입(Actor Reentrancy)으로 인한 세션 혼선 및 데이터 손상 위험

`KeychainTokenStore`는 `actor`로 선언되어 동시성 안전을 보장하는 것처럼 보이지만, `performRefresh` 네트워크 요청을 `await`하는 동안 **액터가 일시 정지(yield)되어 재진입(Reentrancy)이 가능**해집니다.

이 대기 시간 동안 사용자가 로그아웃하고 다른 계정으로 로그인하여 새로운 토큰을 저장할 수 있습니다. 만약 이전 사용자의 토큰 갱신 요청이 뒤늦게 성공하면, **새로 로그인한 사용자의 토큰을 이전 사용자의 갱신된 토큰으로 덮어쓰는 심각한 세션 혼선(Session Hijacking/Corruption) 문제**가 발생할 수 있습니다. 반대로 갱신이 실패한 경우에도 새로 로그인한 사용자의 토큰을 잘못 삭제할 수 있습니다.

**해결 방안:**
네트워크 요청(`await performRefresh`)이 끝난 직후, 키체인에 저장된 `refreshToken`이 요청 시작 시점과 동일한지 검증하는 가드를 추가해야 합니다.

```swift
            do {
                // ③ 갱신 POST.
                let response = try await performRefresh(refreshToken: refreshToken)
                
                // 액터 재진입(Actor Reentrancy) 방어: 네트워크 요청 동안 토큰이 변경되었는지 검증
                guard readItem(account: Account.refresh) == refreshToken else {
                    return
                }
                
                try saveItems(access: response.accessToken, refresh: response.refreshToken)
            } catch let error as URLError {
                // ④-a 네트워크 오류(타임아웃/연결끊김 등) → 인증 실패가 아니므로 토큰 보존·로그아웃 금지(재시도 가능).
                throw error
            } catch let error as KeychainError {
                // ④-b 저장 실패 → 인증 실패가 아니므로 토큰 보존·로그아웃 금지. 상위로 전파.
                throw error
            } catch let error as APIError {
                // ④-c 서버 인증 거부(401 등 APIError) → 로그아웃 유도.
                // 액터 재진입 방어: 현재 토큰이 여전히 동일한 경우에만 삭제 및 로그아웃 수행
                if readItem(account: Account.refresh) == refreshToken {
                    clearItems()
                    await box.handler?()
                }
                throw error
            } catch {
                // ④-d JSON 파싱 실패 등 서버 응답 이상 → 보수적으로 토큰 보존(인증 실패 아님). rethrow only.
                throw error
            }
```

=== ios/WhereWeGo/App/OnboardingRouter.swift:163 [gemini-code-assist[bot]] ===
![high](https://www.gstatic.com/codereviewagent/high-priority.svg)

### ⚠️ 일시적 네트워크 오류 시 SplashView 무한 로딩(Stuck) 버그

`resolveGroupRoute()` 메서드에서 `dependencies.groupAPI.myActiveGroup()` 호출 시 발생한 모든 에러를 빈 `catch` 블록으로 삼키고 있습니다.

만약 401 인증 만료가 아닌 **일시적인 네트워크 연결 끊김이나 타임아웃 에러**가 발생하면, 로그아웃 핸들러가 동작하지 않아 `SessionStore.phase`는 여전히 `.authenticated` 상태를 유지합니다. 하지만 `route` 역시 `.resolvingGroup` 상태로 유지되므로, 사용자는 **아무런 안내나 재시도 기회 없이 SplashView(무한 로딩)에 영원히 갇히게 됩니다.**

**해결 방안:**
401 에러만 로그아웃 처리를 위해 대기하도록 분기하고, 그 외의 일반적인 네트워크 오류 발생 시에는 사용자에게 알림(Alert)을 표시하고 재시도할 수 있는 UI 흐름을 제공해야 합니다.

*팁: `OnboardingRouter`에 `@State private var showErrorAlert = false`와 `@State private var errorText = ""`를 추가하고, `.alert` 수식어를 활용해 재시도 버튼을 배치하는 것을 권장합니다.*

```suggestion
    private func resolveGroupRoute() async {
        do {
            let group = try await dependencies.groupAPI.myActiveGroup()
            if Self.resolveGroupRoute(group: group) == .groupStart {
                route = .groupStart
            } else {
                afterGroupResolved(group)
            }
        } catch {
            if let apiError = error as? APIError, apiError.status == 401 {
                // 401 refresh 실패 → logoutHandler 가 phase 전환하므로 대기
                return
            }
            // TODO: 네트워크 오류 등 일시적 실패 시 사용자에게 알림을 표시하고 재시도할 수 있는 UI 처리가 필요합니다.
        }
    }
```

=== ios/WhereWeGo/Features/Onboarding/NotificationView.swift:24 [gemini-code-assist[bot]] ===
![high](https://www.gstatic.com/codereviewagent/high-priority.svg)

### 🧵 Main Actor 격리 위반으로 인한 런타임 경고/크래시 위험 (Swift 6)

`onAllow()` 메서드는 `async`로 선언되어 있으나 `@MainActor` 격리가 되어 있지 않아, 기본적으로 협력 스레드 풀(cooperative background pool)에서 실행됩니다.

하지만 메서드 내부에서 메인 스레드 전용 API인 `UIApplication.shared` 및 `UIApplication.shared.open(_:)`을 직접 호출하고 있습니다. 이는 Swift 6 모드에서 **Main Actor 격리 위반 컴파일 에러**를 유발하거나, 런타임 시 경고 및 크래시를 발생시킬 수 있습니다.

**해결 방안:**
`onAllow()` 메서드 상단에 `@MainActor` 어트리뷰트를 명시하여 메인 스레드에서 안전하게 실행되도록 보장해야 합니다.

```suggestion
    @MainActor
    private func onAllow() async {
```

=== ios/WhereWeGo/Features/Auth/LoginView.swift:86 [gemini-code-assist[bot]] ===
![medium](https://www.gstatic.com/codereviewagent/medium-priority.svg)

### ♿ VoiceOver 사용자를 위한 접근성(Accessibility) 레이블 누락

Apple 로그인 버튼 구현 시, 순정 `SignInWithAppleButton` 위에 투명한 커스텀 `Button`을 오버레이하여 터치 이벤트를 가로채고 있습니다.

이 투명 버튼의 레이블이 `Color.clear`로 지정되어 있어, **VoiceOver 등 스크린 리더를 사용하는 시각 장애인 사용자는 이 버튼이 무엇인지 인지할 수 없습니다** (단순히 "버튼"으로만 읽히거나 무시됨).

**해결 방안:**
오버레이 버튼에 `.accessibilityLabel("Apple로 로그인")` 수식어를 추가하여 접근성을 확보해 주세요.

```swift
                    Button {
                        Task { await viewModel.loginApple() }
                    } label: {
                        Color.clear
                    }
                    .disabled(viewModel.isLoading)
                    .accessibilityLabel("Apple로 로그인")
```

