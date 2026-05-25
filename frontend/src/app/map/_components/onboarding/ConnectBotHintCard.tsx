"use client";

import { useRouter } from "next/navigation";
import { colors, fonts } from "@/lib/design/tokens";
import { snoozeHint } from "../../_lib/hintSnooze";

interface ConnectBotHintCardProps {
  onDismiss: () => void;
}

/**
 * /map 좌상단 발견성 카드 — 챗봇 매핑이 없는 사용자에게 카톡 챗봇 연동 안내.
 *
 * - "챗봇 연동하기" CTA → /bot/connect 라우트로 이동 (기존 화면).
 * - "×" 닫기 → 3일 snooze + 부모 통지.
 */
export function ConnectBotHintCard({ onDismiss }: ConnectBotHintCardProps) {
  const router = useRouter();

  const onClose = () => {
    snoozeHint("connect-bot");
    onDismiss();
  };

  return (
    <div
      role="region"
      aria-label="챗봇 연동 안내"
      style={{
        position: "absolute",
        top: 16,
        left: 16,
        right: 16,
        maxWidth: 360,
        zIndex: 25,
        background: colors.panel,
        borderRadius: 14,
        border: `1px solid ${colors.hairline}`,
        padding: "14px 18px",
        boxShadow: `0 4px 16px ${colors.shadow}`,
        display: "flex",
        flexDirection: "column",
        gap: 8,
        fontFamily: fonts.sans,
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "flex-start",
          justifyContent: "space-between",
          gap: 12,
        }}
      >
        <div
          style={{
            fontFamily: fonts.emo,
            fontSize: 15,
            fontWeight: 700,
            color: colors.ink,
            lineHeight: 1.4,
          }}
        >
          카톡으로 핀이 저절로
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="닫기"
          style={{
            background: "transparent",
            border: "none",
            padding: 4,
            cursor: "pointer",
            color: colors.inkFaint,
            fontSize: 16,
            lineHeight: 1,
          }}
        >
          ✕
        </button>
      </div>
      <div
        style={{
          fontSize: 13,
          color: colors.inkSoft,
          lineHeight: 1.5,
        }}
      >
        카카오톡 챗봇에 인스타 릴스 링크를 보내면 자동으로 핀이 등록돼요.
      </div>
      <button
        type="button"
        onClick={() => router.push("/bot/connect")}
        style={{
          marginTop: 4,
          alignSelf: "flex-start",
          background: colors.cta,
          color: "#fff",
          border: "none",
          borderRadius: 10,
          padding: "8px 14px",
          fontSize: 13,
          fontWeight: 700,
          cursor: "pointer",
        }}
      >
        챗봇 연동하기
      </button>
    </div>
  );
}
