"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { BackButton } from "@/components/ui/BackButton";
import { issueBotLinkCode } from "@/lib/api/bot-client";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * 카카오톡 'wherewego' 채널 연동용 6자리 코드 발급 화면.
 *
 * - 진입 시 자동으로 `issueBotLinkCode()` 호출 (StrictMode 중복 방지).
 * - 1초 간격 카운트다운. 0초 도달 시 "만료됨" 표시.
 * - 코드값 옆 아이콘으로 즉시 복사. 채널 친구추가 버튼 별도.
 */
const KAKAO_CHANNEL_URL =
  process.env.NEXT_PUBLIC_KAKAO_CHANNEL_URL ?? "https://pf.kakao.com/_HxgdsX/friend";

export function BotConnectClient() {
  const router = useRouter();
  const [code, setCode] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [now, setNow] = useState<number>(() => Date.now());
  const ranRef = useRef(false);

  // 1초 간격 카운트다운 갱신
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const fetchCode = async () => {
    setLoading(true);
    setError(null);
    setCopied(false);
    try {
      const res = await issueBotLinkCode();
      setCode(res.code);
      setExpiresAt(res.expiresAt);
    } catch (e) {
      const message =
        e instanceof Error && e.message
          ? e.message
          : "코드를 발급하지 못했어요. 잠시 후 다시 시도해 주세요.";
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  // 진입 시 1회 발급
  useEffect(() => {
    if (ranRef.current) return;
    ranRef.current = true;
    void fetchCode();
  }, []);

  const { remainingText, expired } = useMemo(() => {
    if (!expiresAt) return { remainingText: "", expired: false };
    const diff = new Date(expiresAt).getTime() - now;
    if (Number.isNaN(diff) || diff <= 0) {
      return { remainingText: "만료됨", expired: true };
    }
    const totalSec = Math.floor(diff / 1000);
    const minutes = Math.floor(totalSec / 60);
    const seconds = totalSec % 60;
    return {
      remainingText: `${minutes}분 ${seconds}초 남음`,
      expired: false,
    };
  }, [expiresAt, now]);

  const onCopy = async () => {
    if (!code) return;
    try {
      if (
        typeof navigator !== "undefined" &&
        navigator.clipboard &&
        typeof navigator.clipboard.writeText === "function"
      ) {
        await navigator.clipboard.writeText(code);
      } else {
        const ta = document.createElement("textarea");
        ta.value = code;
        ta.style.position = "fixed";
        ta.style.opacity = "0";
        document.body.appendChild(ta);
        ta.select();
        document.execCommand("copy");
        document.body.removeChild(ta);
      }
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setError("복사에 실패했어요. 직접 선택해 복사해 주세요.");
    }
  };

  return (
    <div
      style={{
        background: colors.bg,
        minHeight: "100vh",
        fontFamily: fonts.sans,
        display: "flex",
        justifyContent: "center",
        boxSizing: "border-box",
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 460,
          padding: "80px 32px 32px",
          display: "flex",
          flexDirection: "column",
          boxSizing: "border-box",
        }}
      >
        <BackButton onClick={() => router.back()} />
        {/* Heading */}
        <div
          style={{
            fontFamily: fonts.emo,
            fontSize: 32,
            fontWeight: 700,
            color: colors.ink,
            lineHeight: 1.3,
            letterSpacing: -1,
          }}
        >
          카카오톡 챗봇과 연동해요
        </div>
        <div
          style={{
            marginTop: 12,
            fontSize: 14,
            color: colors.inkSoft,
            lineHeight: 1.6,
            whiteSpace: "pre-wrap",
          }}
        >
          {"카카오톡 'wherewego' 채널에 친구추가 후\n아래 코드를 채팅으로 보내주세요"}
        </div>

        {/* 친구추가 버튼 */}
        <a
          href={KAKAO_CHANNEL_URL}
          target="_blank"
          rel="noopener noreferrer"
          style={{
            marginTop: 20,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            gap: 6,
            height: 44,
            borderRadius: 10,
            background: "#FEE500",
            color: "#1A1A2E",
            textDecoration: "none",
            fontFamily: fonts.sans,
            fontSize: 14,
            fontWeight: 700,
            border: "1px solid rgba(0,0,0,0.04)",
          }}
        >
          <span aria-hidden="true">💬</span>
          <span>wherewego 채널 친구추가</span>
        </a>

        {/* Code panel — 코드값 + 복사 아이콘 */}
        <div
          style={{
            marginTop: 24,
            background: colors.panel,
            borderRadius: 16,
            border: `1px solid ${colors.hairline}`,
            padding: "20px 24px",
            minHeight: 100,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            gap: 12,
            boxShadow: `0 4px 14px ${colors.shadow}`,
            position: "relative",
          }}
          aria-live="polite"
        >
          {loading ? (
            <span style={{ color: colors.inkFaint, fontSize: 14 }}>
              코드를 발급하고 있어요...
            </span>
          ) : error && !code ? (
            <span
              style={{ color: colors.cta, fontSize: 14, textAlign: "center" }}
            >
              {error}
            </span>
          ) : code ? (
            <>
              <div
                style={{
                  fontFamily: fonts.mono,
                  fontSize: 40,
                  fontWeight: 700,
                  letterSpacing: 8,
                  color: expired ? colors.inkFaint : colors.ink,
                  textAlign: "center",
                  flex: 1,
                }}
              >
                {code}
              </div>
              {!expired && (
                <button
                  type="button"
                  onClick={onCopy}
                  aria-label={copied ? "복사됨" : "코드 복사"}
                  title={copied ? "복사됨" : "복사"}
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: 8,
                    border: `1px solid ${colors.hairline}`,
                    background: copied ? colors.cta : "transparent",
                    color: copied ? "#fff" : colors.inkSoft,
                    cursor: "pointer",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    flexShrink: 0,
                    transition: "background 120ms ease",
                  }}
                >
                  {copied ? (
                    <svg
                      width="16"
                      height="16"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2.5"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                  ) : (
                    <svg
                      width="16"
                      height="16"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                    </svg>
                  )}
                </button>
              )}
            </>
          ) : null}
        </div>

        {/* Countdown */}
        {expiresAt ? (
          <div
            style={{
              marginTop: 12,
              fontFamily: fonts.mono,
              fontSize: 13,
              color: expired ? colors.cta : colors.inkSoft,
              textAlign: "center",
            }}
          >
            {remainingText}
          </div>
        ) : null}

        {/* Reissue button */}
        <BtnSub
          onClick={fetchCode}
          disabled={loading}
          style={{
            marginTop: 16,
            width: "100%",
            padding: "13px 0",
            fontSize: 14,
          }}
        >
          {loading ? "발급 중..." : "코드 재발급"}
        </BtnSub>

        {error && code ? (
          <div
            role="alert"
            style={{
              marginTop: 12,
              fontSize: 13,
              color: colors.cta,
              textAlign: "center",
            }}
          >
            {error}
          </div>
        ) : null}

        <div style={{ flex: 1 }} />

        <BtnPrimary
          onClick={() => router.back()}
          style={{
            width: "100%",
            padding: "14px 0",
            fontSize: 15,
          }}
        >
          확인
        </BtnPrimary>
      </div>
    </div>
  );
}
