"use client";

import { useRouter } from "next/navigation";

import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { colors, fonts } from "@/lib/design/tokens";

interface Step1GroupProps {
  onSkip: () => void;
}

/**
 * 위저드 Step 1 — 그룹 시작.
 *
 * - "새 그룹 만들기" → /groups/new. 생성 완료 후 사용자가 위저드 재진입 시 자동 Step 2 노출.
 * - "초대 코드로 합류" → /onboarding/invite-code (기존 흐름 그대로 — 합류 성공 시 /groups).
 * - "다음에 할게요" → onSkip (Step 2 로 진행).
 *
 * onCompleted prop 은 다른 Step 과의 인터페이스 일관성용이지만 현재 구현은 라우팅으로 자체 종결되므로 제외.
 */
export function Step1Group({ onSkip }: Step1GroupProps) {
  const router = useRouter();

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
        함께 갈 곳을 모아봐요
      </div>
      <div
        style={{
          marginTop: 10,
          fontSize: 14,
          color: colors.inkSoft,
          lineHeight: 1.6,
        }}
      >
        새 그룹을 만들거나 초대받은 그룹에 합류할 수 있어요.
      </div>

      <div style={{ flex: 1 }} />

      <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <BtnPrimary
          onClick={() => router.push("/groups/new?from=welcome")}
          style={{ width: "100%", padding: "14px 0", fontSize: 15 }}
        >
          새 그룹 만들기
        </BtnPrimary>
        <BtnSub
          onClick={() => router.push("/onboarding/invite-code")}
          style={{ width: "100%", padding: "13px 0", fontSize: 14 }}
        >
          초대 코드로 합류
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
