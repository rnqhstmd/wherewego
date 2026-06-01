"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { GlobeBg } from "@/components/ui/GlobeBg";
import { postKakaoCallback } from "@/lib/api/auth";
import { apiFetch, ApiError } from "@/lib/api/http-client";
import { kakaoState } from "@/lib/oauth/kakao-state";
import { returnUrlStash } from "@/lib/oauth/return-url";
import { locationAsked, nicknameSet } from "@/lib/storage/local-flags";
import { colors, fonts } from "@/lib/design/tokens";
import type { ActiveGroupResponse } from "@/lib/api/types";

// 로그인 콜백이 3초 이상 걸리면(= Neon 콜드 스타트) 기존 "잠시만요" 대신 노출되는 대기 문구.
// 베타 한정 인사이드 조크 — 외부 공개 시 이 배열만 교체하면 된다.
const COLD_START_MESSAGES = [
  "개발자 호출하는 중..",
  "본슨씨..일어나..일해야지",
  "서버 깨우는 중..",
] as const;

/**
 * Screen 0a — 카카오 콜백 처리 화면 (screens-login.jsx::Screen0aLoading 1:1 UI).
 *
 * 1) state 검증 → 실패 시 /login?error=invalid_state.
 * 2) code 없음 → /login?error=oauth_failed.
 * 3) postKakaoCallback → 실패 시 /login?error=oauth_failed.
 * 4) 활성 그룹 조회 → 분기:
 *    - 그룹 있음: returnUrl(/map|/pins) 또는 /map
 *    - 그룹 없음 + 닉네임 설정 완료: /onboarding/welcome (Phase 11 PR-B — 위저드 진입)
 *    - 그룹 없음 + 닉네임 미설정: /onboarding/nickname
 */
async function fetchActiveGroupClient(): Promise<ActiveGroupResponse | null> {
  // 콜백 시점에는 막 토큰을 받은 직후이므로 401 발생 가능성은 낮지만
  // 안전망으로 401 → null fallback 유지. 그 외 오류는 상위로 전파.
  // parseResponse 는 data:null 을 undefined 로 변환하므로 명시적으로 null 정규화.
  try {
    const result = await apiFetch<ActiveGroupResponse | null>("/groups/me");
    return result ?? null;
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

  // (a) 처음 3초는 "잠시만요" 유지 → 콜드 스타트로 판단되면 유머 문구를 5초 주기로 교체.
  // null = 아직 3초 전(기본 문구). 마운트 시 순서를 무작위 셔플해 세션마다 다르게 보인다.
  const [coldMsg, setColdMsg] = useState<string | null>(null);

  useEffect(() => {
    const order = [...COLD_START_MESSAGES]
      .map((m) => ({ m, r: Math.random() }))
      .sort((a, b) => a.r - b.r)
      .map((x) => x.m);
    let idx = 0;
    let rotate: ReturnType<typeof setInterval> | undefined;
    const kickoff = setTimeout(() => {
      setColdMsg(order[idx]);
      rotate = setInterval(() => {
        idx = (idx + 1) % order.length;
        setColdMsg(order[idx]);
      }, 5000);
    }, 3000);
    return () => {
      clearTimeout(kickoff);
      if (rotate) clearInterval(rotate);
    };
  }, []);

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
      // 1) 최종 목적지(다음 단계) 결정
      // 그룹이 있어도 기본적으로 그룹 목록 화면(/groups)으로 진입 →
      // 사용자가 거기서 그룹 카드를 눌러야 /map으로 이동한다.
      let target: string;
      if (group !== null) {
        target = stashedReturnUrl ?? "/groups";
      } else if (nicknameSet.get()) {
        // TODO(보안 강화): 현재 신규 판정은 localStorage('maygo:nickname-set') flag에만 의존.
        // 백엔드(AuthService.java)는 카카오 닉네임을 그대로 채워 식별 가능한 기본값 패턴이 없으므로
        // 서버 응답의 user.nickname으로 신규/기존을 구분할 수 없음. 추후 백엔드에
        // `nicknameConfirmed: boolean` 필드 추가 시 그것을 1차 기준으로 사용하도록 교체할 것.
        // Phase 11 PR-B: 그룹 없는 신규 사용자는 3단계 위저드로 진입한다.
        // 위저드 내부에서 그룹 만들기/합류, 초대 링크 공유, 챗봇 연동을 안내한다.
        target = "/onboarding/welcome";
      } else {
        target = "/onboarding/nickname";
      }

      // 2) 위치 권한을 한 번도 안내한 적이 없으면 카카오 로그인 직후 우선 노출.
      //    응답(허용/나중에) 후 위 target 으로 이어진다.
      if (!locationAsked.get()) {
        router.replace(
          `/onboarding/location?next=${encodeURIComponent(target)}`,
        );
        return;
      }

      router.replace(target);
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
          key={coldMsg ?? "wait"}
          style={{
            fontFamily: fonts.emo,
            fontSize: 24,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -0.5,
            textAlign: "center",
            padding: "0 24px",
            // 문구가 바뀔 때만(key 변경 → remount) fade-in 재생. 초기 "잠시만요"는 애니메이션 없음.
            animation: coldMsg ? "coldstart-msg-in 0.45s ease-out" : undefined,
          }}
        >
          {coldMsg ?? "잠시만요"}
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
