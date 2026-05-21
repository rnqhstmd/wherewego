"use client";

import { Suspense, useState, useTransition } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { BtnKakao } from "@/components/ui/BtnKakao";
import { GlobeBg } from "@/components/ui/GlobeBg";
import { PinDot } from "@/components/ui/PinDot";
import { getKakaoLoginUrl } from "@/lib/api/auth";
import { kakaoState } from "@/lib/oauth/kakao-state";
import { returnUrlStash } from "@/lib/oauth/return-url";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * 첫 진입 게이트 화면. 로그인 화면과 동일 디자인.
 *
 * 2단계 UX:
 *   (1) 코드 입력 단계: 6자리 초대 코드 입력 → /api/auth/gate POST → 쿠키 발급
 *   (2) 통과 단계: 같은 화면에서 [카카오로 시작하기] 버튼 + 약관 안내 노출
 *
 * 통과 전엔 모든 라우트가 middleware에 의해 이 페이지로 강제 redirect된다.
 */
function GatePageInner() {
  const router = useRouter();
  const params = useSearchParams();
  const returnUrl = params.get("returnUrl") ?? "/";

  const [code, setCode] = useState("");
  const [verified, setVerified] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const [kakaoSubmitting, setKakaoSubmitting] = useState(false);
  const [focused, setFocused] = useState(false);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value.replace(/\D/g, "").slice(0, 6);
    setCode(next);
    if (error) setError(null);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (verified || code.length !== 6) {
      if (code.length !== 6) setError("6자리 코드를 입력해 주세요");
      return;
    }
    setError(null);
    startTransition(async () => {
      try {
        const res = await fetch("/api/auth/gate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ code }),
        });
        if (res.ok) {
          setVerified(true);
          return;
        }
        const data = await res.json().catch(() => ({}));
        setError(data?.message ?? "코드 확인에 실패했습니다");
      } catch {
        setError("네트워크 오류가 발생했습니다");
      }
    });
  };

  const onKakaoClick = async () => {
    if (kakaoSubmitting) return;
    setKakaoSubmitting(true);
    try {
      returnUrlStash.set(returnUrl);
      const state = kakaoState.generate();
      const { loginUrl } = await getKakaoLoginUrl();
      const separator = loginUrl.includes("?") ? "&" : "?";
      window.location.assign(
        `${loginUrl}${separator}state=${encodeURIComponent(state)}`,
      );
    } catch {
      setKakaoSubmitting(false);
      router.replace("/gate?error=oauth_failed");
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
        {/* Brand wordmark — 작은 화면(360px대)에서 한 줄 유지를 위해 viewport 기반 축소 */}
        <div
          style={{
            fontFamily: fonts.emo,
            fontSize: "clamp(36px, 11vw, 56px)",
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -1.5,
            lineHeight: 1.05,
            whiteSpace: "nowrap",
          }}
        >
          우리가 갈 지도
        </div>

        {/* Tagline — 항상 동일 (단계 무관) */}
        <div
          style={{
            marginTop: 28,
            fontFamily: fonts.sans,
            fontSize: 15.5,
            color: colors.inkSoft,
            lineHeight: 1.5,
          }}
        >
          우리의 장소를 지도 위에 아카이빙해요
        </div>
        <div
          style={{
            marginTop: 6,
            fontFamily: fonts.sans,
            fontSize: 13,
            color: colors.inkFaint,
            lineHeight: 1.5,
          }}
        >
          초대받은 분만 입장할 수 있어요
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

        {error ? (
          <div
            role="alert"
            style={{
              marginTop: 18,
              fontSize: 12.5,
              color: colors.cta,
              lineHeight: 1.5,
            }}
          >
            {error}
          </div>
        ) : null}

        {/* (1) 코드 입력 단계 */}
        {!verified ? (
          <form
            onSubmit={handleSubmit}
            style={{
              marginTop: 32,
              width: "100%",
              maxWidth: 320,
              display: "flex",
              flexDirection: "column",
              gap: 14,
            }}
          >
            <input
              type="text"
              inputMode="numeric"
              pattern="[0-9]*"
              value={code}
              onChange={handleChange}
              onFocus={() => setFocused(true)}
              onBlur={() => setFocused(false)}
              autoFocus
              autoComplete="one-time-code"
              maxLength={6}
              disabled={pending}
              placeholder={focused ? "" : "● ● ● ● ● ●"}
              aria-label="초대 코드"
              style={{
                width: "100%",
                height: 52,
                padding: "0 12px",
                borderRadius: 12,
                border: `1px solid ${colors.hairline}`,
                background: "#fff",
                outline: "none",
                textAlign: "center",
                letterSpacing: 10,
                fontFamily: "var(--font-mono), 'Menlo', monospace",
                fontSize: 22,
                fontWeight: 700,
                color: colors.ink,
              }}
            />
            <button
              type="submit"
              disabled={pending || code.length !== 6}
              style={{
                height: 48,
                borderRadius: 12,
                border: "none",
                background:
                  pending || code.length !== 6 ? "#C5C5D0" : colors.cta,
                color: "#fff",
                fontFamily: "inherit",
                fontSize: 15,
                fontWeight: 700,
                cursor:
                  pending || code.length !== 6 ? "not-allowed" : "pointer",
              }}
            >
              {pending ? "확인 중..." : "입장"}
            </button>
          </form>
        ) : (
          <>
            {/* (2) 통과 단계 — 카카오 시작하기 */}
            <BtnKakao
              onClick={onKakaoClick}
              disabled={kakaoSubmitting}
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

            <div
              style={{
                marginTop: 18,
                fontSize: 12,
                color: colors.inkFaint,
                lineHeight: 1.5,
              }}
            >
              시작하면 서비스 이용약관 및 개인정보처리방침에 동의합니다
            </div>
          </>
        )}
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

export default function GatePage() {
  return (
    <Suspense fallback={null}>
      <GatePageInner />
    </Suspense>
  );
}
