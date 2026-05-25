"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { issueBotLinkCode } from "@/lib/api/bot-client";
import { colors, fonts } from "@/lib/design/tokens";

interface Step3BotProps {
  onCompleted: () => void;
  onSkip: () => void;
}

const KAKAO_CHANNEL_URL =
  process.env.NEXT_PUBLIC_KAKAO_CHANNEL_URL ?? "https://pf.kakao.com/_HxgdsX/friend";

/**
 * 위저드 Step 3 — 챗봇 연동.
 *
 * 진입 시 6자리 연동 코드 발급. 만료까지 카운트다운 표시.
 * "카카오톡 챗봇 추가하기" 외부 링크 + "다음에 할게요" 허용.
 *
 * 실제 연동 완료(매핑 INSERT)는 사용자가 카톡에서 코드를 입력하는 시점이므로,
 * 위저드는 발급/공유까지만 안내하고 onCompleted 는 사용자가 "완료" 버튼을 눌렀을 때 호출한다.
 */
export function Step3Bot({ onCompleted, onSkip }: Step3BotProps) {
  const [code, setCode] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState<number>(() => Date.now());
  const ranRef = useRef(false);

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (ranRef.current) return;
    ranRef.current = true;
    void (async () => {
      try {
        const res = await issueBotLinkCode();
        setCode(res.code);
        setExpiresAt(res.expiresAt);
      } catch (e) {
        setError(
          e instanceof Error && e.message
            ? e.message
            : "연동 코드를 발급하지 못했어요. 잠시 후 다시 시도해 주세요.",
        );
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const remainingText = useMemo(() => {
    if (!expiresAt) return "";
    const diff = new Date(expiresAt).getTime() - now;
    if (Number.isNaN(diff) || diff <= 0) return "만료됨";
    const totalSec = Math.floor(diff / 1000);
    const minutes = Math.floor(totalSec / 60);
    const seconds = totalSec % 60;
    return `${minutes}분 ${seconds}초 남음`;
  }, [expiresAt, now]);

  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        height: "100%",
        minHeight: 400,
      }}
    >
      <div
        style={{
          fontFamily: fonts.emo,
          fontSize: 26,
          fontWeight: 700,
          color: colors.ink,
          lineHeight: 1.3,
          letterSpacing: -1,
        }}
      >
        카톡으로 핀을 자동 저장해요
      </div>
      <div
        style={{
          marginTop: 10,
          fontSize: 14,
          color: colors.inkSoft,
          lineHeight: 1.6,
        }}
      >
        인스타 릴스 링크를 챗봇에 보내면 장소가 자동으로 핀으로 저장돼요.
      </div>

      <div
        style={{
          marginTop: 32,
          background: colors.panel,
          borderRadius: 14,
          border: `1px solid ${colors.hairline}`,
          padding: "24px 22px",
          display: "flex",
          flexDirection: "column",
          gap: 8,
          alignItems: "center",
        }}
        aria-live="polite"
      >
        <div
          style={{
            fontSize: 12,
            color: colors.inkFaint,
            letterSpacing: 0.5,
          }}
        >
          연동 코드
        </div>
        <div
          style={{
            fontFamily: fonts.mono,
            fontSize: 32,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: 6,
            minHeight: 40,
          }}
        >
          {loading ? "..." : error ? "—" : code}
        </div>
        <div
          style={{
            fontFamily: fonts.mono,
            fontSize: 12,
            color: colors.inkSoft,
          }}
        >
          {expiresAt ? remainingText : ""}
        </div>
      </div>

      {error ? (
        <div
          role="alert"
          style={{
            marginTop: 10,
            fontSize: 13,
            color: colors.cta,
          }}
        >
          {error}
        </div>
      ) : null}

      <div style={{ flex: 1 }} />

      <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <BtnPrimary
          onClick={() => window.open(KAKAO_CHANNEL_URL, "_blank", "noopener,noreferrer")}
          disabled={loading || !code}
          style={{ width: "100%", padding: "14px 0", fontSize: 15 }}
        >
          카카오톡 챗봇 추가하기
        </BtnPrimary>
        <BtnSub
          onClick={onCompleted}
          style={{ width: "100%", padding: "13px 0", fontSize: 14 }}
        >
          완료
        </BtnSub>
        <button
          type="button"
          onClick={onSkip}
          style={{
            marginTop: 4,
            background: "transparent",
            border: "none",
            color: colors.inkFaint,
            fontSize: 13,
            padding: "6px 0",
            cursor: "pointer",
          }}
        >
          다음에 할게요
        </button>
      </div>
    </div>
  );
}
