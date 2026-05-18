"use client";

import { Suspense, useEffect, useRef } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { GlobeBg } from "@/components/ui/GlobeBg";
import { postKakaoCallback } from "@/lib/api/auth";
import { apiFetch, ApiError } from "@/lib/api/http-client";
import { kakaoState } from "@/lib/oauth/kakao-state";
import { returnUrlStash } from "@/lib/oauth/return-url";
import { nicknameSet } from "@/lib/storage/local-flags";
import { colors, fonts } from "@/lib/design/tokens";
import type { ActiveGroupResponse } from "@/lib/api/types";

/**
 * Screen 0a — 카카오 콜백 처리 화면 (screens-login.jsx::Screen0aLoading 1:1 UI).
 *
 * 1) state 검증 → 실패 시 /login?error=invalid_state.
 * 2) code 없음 → /login?error=oauth_failed.
 * 3) postKakaoCallback → 실패 시 /login?error=oauth_failed.
 * 4) 활성 그룹 조회 → 분기:
 *    - 그룹 있음: returnUrl(/map|/pins) 또는 /map
 *    - 그룹 없음 + 닉네임 설정 완료: /onboarding/group-start
 *    - 그룹 없음 + 닉네임 미설정: /onboarding/nickname
 */
async function fetchActiveGroupClient(): Promise<ActiveGroupResponse | null> {
  // 콜백 시점에는 막 토큰을 받은 직후이므로 401 발생 가능성은 낮지만
  // 안전망으로 401 → null fallback 유지. 그 외 오류는 상위로 전파.
  try {
    return await apiFetch<ActiveGroupResponse | null>("/groups/me");
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      return null;
    }
    throw e;
  }
}

function CallbackInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const ranRef = useRef(false);

  useEffect(() => {
    // React 18 StrictMode dev 중복 실행 방지 + state 1회 소비 보호
    if (ranRef.current) return;
    ranRef.current = true;

    const code = searchParams.get("code");
    const state = searchParams.get("state");
    // EC-003: 카카오는 redirect_uri 외 임의 쿼리를 보장 보존하지 않으므로
    // LoginClient 가 stash 한 returnUrl 을 sessionStorage 에서 1회 소비.
    const stashedReturnUrl = returnUrlStash.consume();

    if (!kakaoState.validate(state)) {
      router.replace("/login?error=invalid_state");
      return;
    }
    if (!code) {
      router.replace("/login?error=oauth_failed");
      return;
    }

    void (async () => {
      try {
        await postKakaoCallback({ code });
      } catch {
        router.replace("/login?error=oauth_failed");
        return;
      }

      const group = await fetchActiveGroupClient();
      if (group !== null) {
        const target = stashedReturnUrl ?? "/map";
        router.replace(target);
        return;
      }

      // TODO(보안 강화): 현재 신규 판정은 localStorage('maygo:nickname-set') flag에만 의존.
      // 백엔드(AuthService.java)는 카카오 닉네임을 그대로 채워 식별 가능한 기본값 패턴이 없으므로
      // 서버 응답의 user.nickname으로 신규/기존을 구분할 수 없음. 추후 백엔드에
      // `nicknameConfirmed: boolean` 필드 추가 시 그것을 1차 기준으로 사용하도록 교체할 것.
      if (nicknameSet.get()) {
        router.replace("/onboarding/group-start");
        return;
      }
      router.replace("/onboarding/nickname");
    })();
  }, [router, searchParams]);

  return (
    <div
      style={{
        width: "100%",
        height: "100vh",
        background: colors.bg,
        fontFamily: fonts.sans,
        position: "relative",
        overflow: "hidden",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <div
        style={{
          position: "absolute",
          inset: 0,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <GlobeBg w={520} h={520} style={{ opacity: 0.35 }} />
      </div>

      <div
        style={{
          position: "relative",
          zIndex: 2,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 24,
        }}
      >
        <div
          aria-label="로딩 중"
          style={{
            width: 48,
            height: 48,
            borderRadius: "50%",
            border: `3px solid ${colors.hairline}`,
            borderTopColor: colors.cta,
            animation: "spin 1s linear infinite",
          }}
        />
        <div
          style={{
            fontFamily: fonts.emo,
            fontSize: 24,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -0.5,
          }}
        >
          잠시만요
        </div>
        <div
          style={{
            fontFamily: fonts.sans,
            fontSize: 14,
            color: colors.inkSoft,
            textAlign: "center",
            lineHeight: 1.6,
          }}
        >
          카카오로 로그인하고 있어요
        </div>
      </div>

    </div>
  );
}

export default function LoginCallbackPage() {
  return (
    <Suspense
      fallback={
        <div
          style={{
            width: "100%",
            height: "100vh",
            background: colors.bg,
          }}
        />
      }
    >
      <CallbackInner />
    </Suspense>
  );
}
