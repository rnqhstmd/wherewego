"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { BtnSub } from "@/components/ui/BtnSub";
import { issueBotLinkCode } from "@/lib/api/bot-client";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * 카카오톡 챗봇 'MayGo' 채널 연동용 6자리 코드 발급 화면.
 *
 * - 진입 시 자동으로 `issueBotLinkCode()` 호출 (StrictMode 중복 방지).
 * - 1초 간격 카운트다운. 0초 도달 시 "만료됨" 표시.
 * - "코드 재발급" 버튼 → 새 코드 발급.
 */
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
        padding: "70px 28px 32px",
        background: colors.bg,
        minHeight: "100vh",
        fontFamily: fonts.sans,
        display: "flex",
        flexDirection: "column",
        boxSizing: "border-box",
      }}
    >
      {/* Heading */}
      <div
        style={{
          fontFamily: fonts.emo,
          fontSize: 28,
          fontWeight: 700,
          color: colors.ink,
          lineHeight: 1.3,
          letterSpacing: -1,
        }}
      >
        카카오톡 챗봇 연동
      </div>
      <div
        style={{
          marginTop: 10,
          fontSize: 14,
          color: colors.inkSoft,
          lineHeight: 1.6,
          whiteSpace: "pre-wrap",
        }}
      >
        {"카카오톡 'MayGo' 채널에 친구추가 후\n아래 코드를 채팅으로 보내주세요"}
      </div>

      {/* Code panel */}
      <div
        style={{
          marginTop: 32,
          background: colors.panel,
          borderRadius: 16,
          border: `1px solid ${colors.hairline}`,
          padding: 24,
          minHeight: 100,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          boxShadow: `0 4px 14px ${colors.shadow}`,
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
          <div
            style={{
              fontFamily: fonts.mono,
              fontSize: 44,
              fontWeight: 700,
              letterSpacing: 10,
              color: expired ? colors.inkFaint : colors.ink,
              textAlign: "center",
            }}
          >
            {code}
          </div>
        ) : null}
      </div>

      {/* Copy small button */}
      {code && !expired ? (
        <div
          style={{
            marginTop: 12,
            display: "flex",
            justifyContent: "flex-end",
          }}
        >
          <BtnSub
            onClick={onCopy}
            style={{ padding: "8px 16px", fontSize: 13 }}
          >
            {copied ? "복사됨" : "복사"}
          </BtnSub>
        </div>
      ) : null}

      {/* Countdown */}
      {expiresAt ? (
        <div
          style={{
            marginTop: 16,
            fontFamily: fonts.mono,
            fontSize: 14,
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
          marginTop: 20,
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

      <BtnSub
        onClick={() => router.back()}
        style={{
          width: "100%",
          padding: "13px 0",
          fontSize: 14,
        }}
      >
        닫기
      </BtnSub>
    </div>
  );
}
