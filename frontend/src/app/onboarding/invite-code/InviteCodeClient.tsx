"use client";

import { Suspense, useState, type ChangeEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { acceptInviteLink } from "@/lib/api/group-client";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * 초대 코드(토큰) 입력 → 그룹 합류 화면.
 *
 * - useSearchParams로 `?token=`을 자동 prefill (Suspense로 감쌈).
 * - "합류하기" 클릭 → acceptInviteLink → /groups + refresh.
 * - 실패 시 단일 메시지 ("잘못된 코드이거나 만료되었어요").
 */
function InviteCodeInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  // ?token= 을 lazy initializer에서 1회 읽어 prefill.
  // (effect 내 setState는 cascading render를 유발하므로 회피)
  const [token, setToken] = useState<string>(
    () => searchParams.get("token") ?? "",
  );
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const trimmed = token.trim();
  const canSubmit = trimmed.length > 0 && !submitting;

  const onChange = (e: ChangeEvent<HTMLInputElement>) => {
    setToken(e.target.value);
    if (error) setError(null);
  };

  const onSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      await acceptInviteLink(trimmed);
      router.replace("/groups");
      router.refresh();
    } catch {
      setSubmitting(false);
      setError("잘못된 코드이거나 만료되었어요");
    }
  };

  return (
    <div
      style={{
        padding: "80px 32px 32px",
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
          fontSize: 32,
          fontWeight: 700,
          color: colors.ink,
          lineHeight: 1.3,
          letterSpacing: -1,
          whiteSpace: "pre-wrap",
        }}
      >
        초대 코드를 받았나요?
      </div>

      {/* Sub */}
      <div
        style={{
          marginTop: 12,
          fontSize: 14,
          color: colors.inkSoft,
          lineHeight: 1.6,
        }}
      >
        친구가 보낸 링크의 코드를 입력해요
      </div>

      {/* Input */}
      <div
        style={{
          marginTop: 40,
          borderBottom: `2px solid ${colors.cta}`,
          padding: "0 0 8px 0",
        }}
      >
        <input
          type="text"
          value={token}
          onChange={onChange}
          placeholder="초대 코드"
          autoFocus
          aria-label="초대 코드"
          style={{
            width: "100%",
            border: "none",
            background: "transparent",
            fontFamily: fonts.emo,
            fontSize: 22,
            fontWeight: 700,
            color: colors.ink,
            outline: "none",
            padding: 0,
          }}
        />
      </div>

      {/* Error */}
      {error ? (
        <div
          role="alert"
          style={{
            marginTop: 12,
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
          onClick={onSubmit}
          disabled={!canSubmit}
          style={{ width: "100%", padding: "14px 0", fontSize: 15 }}
        >
          {submitting ? "합류 중..." : "합류하기"}
        </BtnPrimary>
        <BtnSub
          onClick={() => router.back()}
          style={{ width: "100%", padding: "13px 0", fontSize: 14 }}
        >
          취소
        </BtnSub>
      </div>
    </div>
  );
}

export function InviteCodeClient() {
  return (
    <Suspense
      fallback={
        <div
          style={{
            width: "100%",
            minHeight: "100vh",
            background: colors.bg,
          }}
        />
      }
    >
      <InviteCodeInner />
    </Suspense>
  );
}
