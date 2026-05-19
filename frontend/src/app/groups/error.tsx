"use client";

import { useEffect, useState } from "react";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { colors, fonts } from "@/lib/design/tokens";

interface GroupsErrorProps {
  error: Error & { digest?: string };
  reset: () => void;
}

/**
 * /groups error boundary (Next.js error.tsx).
 *
 * - 재시도 3회까지는 BtnPrimary("다시 시도")로 reset 호출.
 * - 3회 초과 시 안내 텍스트만 노출.
 */
export default function GroupsError({ error, reset }: GroupsErrorProps) {
  const [retries, setRetries] = useState(0);

  useEffect(() => {
    // 운영에서는 logger로 교체. 개발 환경에서만 전체 error 객체 출력.
    if (process.env.NODE_ENV !== "production") {
      console.error("[/groups] error boundary:", error);
    } else {
      console.error("[/groups] error:", error.message);
    }
  }, [error]);

  const onRetry = () => {
    setRetries((r) => r + 1);
    reset();
  };

  return (
    <div
      style={{
        minHeight: "100vh",
        background: colors.bg,
        fontFamily: fonts.sans,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        padding: 40,
        gap: 16,
      }}
    >
      <div
        style={{
          fontFamily: fonts.emo,
          fontSize: 22,
          fontWeight: 700,
          color: colors.ink,
          letterSpacing: -0.5,
          textAlign: "center",
        }}
      >
        그룹 정보를 불러올 수 없어요
      </div>
      <div
        style={{
          fontSize: 13.5,
          color: colors.inkSoft,
          lineHeight: 1.6,
          textAlign: "center",
        }}
      >
        잠시 후 다시 시도해 주세요
      </div>

      {retries < 3 ? (
        <BtnPrimary onClick={onRetry} style={{ padding: "12px 24px" }}>
          다시 시도
        </BtnPrimary>
      ) : (
        <div
          style={{
            color: colors.inkFaint,
            fontSize: 13,
            textAlign: "center",
          }}
        >
          잠시 후 다시 접속해 주세요.
        </div>
      )}
    </div>
  );
}
