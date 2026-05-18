"use client";

import { useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { BtnKakao } from "@/components/ui/BtnKakao";
import { GlobeBg } from "@/components/ui/GlobeBg";
import { PinDot } from "@/components/ui/PinDot";
import { getKakaoLoginUrl } from "@/lib/api/auth";
import { kakaoState } from "@/lib/oauth/kakao-state";
import { returnUrlStash } from "@/lib/oauth/return-url";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * Screen 0 — 로그인 화면 (screens-login.jsx::Screen0Login 1:1).
 *
 * - 카카오 시작 버튼: state 생성 → loginUrl 조회 → state 부착 후 redirect.
 * - URL ?error=oauth_failed | invalid_state 노출.
 */
export function LoginClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const errorParam = searchParams.get("error");
  const [submitting, setSubmitting] = useState(false);

  const errorMessage = useMemo(() => {
    if (errorParam === "oauth_failed" || errorParam === "invalid_state") {
      return "로그인에 실패했어요. 다시 시도해 주세요";
    }
    if (errorParam === "session_expired") {
      // EC-004: refresh token 만료 등으로 세션이 끊긴 경우.
      return "세션이 만료되었어요. 다시 로그인해 주세요";
    }
    return null;
  }, [errorParam]);

  const onKakaoClick = async () => {
    if (submitting) return;
    setSubmitting(true);
    try {
      // EC-003: 가드가 붙여 보낸 returnUrl 을 카카오 왕복 사이에 sessionStorage 로 보존.
      // 카카오는 redirect_uri 외 임의 쿼리를 보장 보존하지 않으므로 클라 stash 로 처리.
      returnUrlStash.set(searchParams.get("returnUrl"));
      const state = kakaoState.generate();
      const { loginUrl } = await getKakaoLoginUrl();
      const separator = loginUrl.includes("?") ? "&" : "?";
      window.location.assign(
        `${loginUrl}${separator}state=${encodeURIComponent(state)}`,
      );
    } catch {
      setSubmitting(false);
      router.replace("/login?error=oauth_failed");
    }
  };

  return (
    <div
      style={{
        position: "relative",
        width: "100%",
        height: "100vh",
        background: colors.bg,
        fontFamily: fonts.sans,
        overflow: "hidden",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      {/* Globe background */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <GlobeBg w={680} h={680} style={{ opacity: 0.55 }} />
      </div>

      {/* Center card */}
      <div
        style={{
          position: "relative",
          zIndex: 2,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          maxWidth: 460,
          width: "90%",
          padding: 20,
          textAlign: "center",
        }}
      >
        {/* Brand wordmark */}
        <div
          style={{
            fontFamily: fonts.emo,
            fontSize: 56,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -1.5,
            lineHeight: 1.05,
            textAlign: "center",
          }}
        >
          우리가 갈 지도
        </div>

        {/* Tagline */}
        <div
          style={{
            marginTop: 28,
            fontFamily: fonts.sans,
            fontSize: 15.5,
            color: colors.inkSoft,
            textAlign: "center",
            lineHeight: 1.5,
          }}
        >
          우리의 장소를 지도 위에 아카이빙해요
        </div>

        {/* Divider dots */}
        <div
          style={{
            display: "flex",
            gap: 8,
            marginTop: 32,
            alignItems: "center",
          }}
        >
          <PinDot type="place" size={8} />
          <PinDot type="memory" size={11} />
          <PinDot type="place" size={8} />
        </div>

        {/* Error message */}
        {errorMessage ? (
          <div
            role="alert"
            style={{
              marginTop: 18,
              fontSize: 12.5,
              color: colors.cta,
              textAlign: "center",
              lineHeight: 1.5,
            }}
          >
            {errorMessage}
          </div>
        ) : null}

        {/* Kakao button */}
        <BtnKakao
          onClick={onKakaoClick}
          disabled={submitting}
          style={{ marginTop: 32, maxWidth: 320 }}
        >
          <svg
            width="20"
            height="20"
            viewBox="0 0 20 20"
            style={{ flexShrink: 0 }}
            aria-hidden="true"
          >
            <path
              d="M10 2C5.58 2 2 4.88 2 8.4c0 2.26 1.5 4.24 3.76 5.37l-.96 3.55 4.12-2.72c.36.05.72.07 1.08.07 4.42 0 8-2.88 8-6.4S14.42 2 10 2z"
              fill={colors.kakaoInk}
            />
          </svg>
          카카오로 시작하기
        </BtnKakao>

        {/* Terms notice */}
        <div
          style={{
            marginTop: 18,
            fontSize: 12,
            color: colors.inkFaint,
            textAlign: "center",
            lineHeight: 1.5,
          }}
        >
          시작하면 서비스 이용약관 및 개인정보처리방침에 동의합니다
        </div>
      </div>

      {/* Corner dots */}
      <div
        style={{
          position: "absolute",
          bottom: 28,
          left: 28,
          display: "flex",
          gap: 5,
          opacity: 0.5,
        }}
      >
        {[colors.pinPlace, colors.pinMemory, colors.inkFaint].map((c, i) => (
          <div
            key={i}
            style={{
              width: 6,
              height: 6,
              borderRadius: "50%",
              background: c,
            }}
          />
        ))}
      </div>
    </div>
  );
}
