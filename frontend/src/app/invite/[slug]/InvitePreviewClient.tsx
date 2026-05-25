"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";

import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { acceptInviteLink } from "@/lib/api/group-client";
import { ApiError } from "@/lib/api/http-client";
import { colors, fonts } from "@/lib/design/tokens";

import type { InviteLinkPreviewResponse } from "@/lib/api/group-client";

interface InvitePreviewClientProps {
  slug: string;
  preview: InviteLinkPreviewResponse;
}

/**
 * 단축 슬러그 진입 후 보이는 미리보기.
 *
 * - "합류하기" 클릭 → acceptInviteLink(token).
 *   401(미로그인) → `/login?returnUrl=/invite/{slug}` 로 보낸다.
 *   200 → `/groups` 로 이동.
 *   기타 4xx → 화면 내 에러 표시.
 * - "취소" → 이전 페이지로 복귀.
 */
export function InvitePreviewClient({ slug, preview }: InvitePreviewClientProps) {
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState<number>(() => Date.now());

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const remainingText = useMemo(() => {
    const diff = new Date(preview.expiresAt).getTime() - now;
    if (Number.isNaN(diff) || diff <= 0) return "곧 만료돼요";
    const totalMin = Math.floor(diff / 60000);
    const days = Math.floor(totalMin / (60 * 24));
    const hours = Math.floor((totalMin % (60 * 24)) / 60);
    const minutes = totalMin % 60;
    if (days > 0) return `${days}일 ${hours}시간 남음`;
    if (hours > 0) return `${hours}시간 ${minutes}분 남음`;
    return `${minutes}분 남음`;
  }, [preview.expiresAt, now]);

  const onAccept = async () => {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await acceptInviteLink(preview.token);
      router.replace("/groups");
      router.refresh();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        router.replace(`/login?returnUrl=${encodeURIComponent(`/invite/${slug}`)}`);
        return;
      }
      const message =
        e instanceof ApiError
          ? e.message
          : "합류하지 못했어요. 잠시 후 다시 시도해 주세요.";
      setSubmitting(false);
      setError(message);
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
        <div
          style={{
            fontFamily: fonts.emo,
            fontSize: 28,
            fontWeight: 700,
            color: colors.ink,
            lineHeight: 1.3,
            letterSpacing: -1,
            whiteSpace: "pre-wrap",
          }}
        >
          {preview.inviterNickname}님이{"\n"}당신을 초대했어요
        </div>

        <div
          style={{
            marginTop: 12,
            fontSize: 14,
            color: colors.inkSoft,
            lineHeight: 1.6,
          }}
        >
          함께 갈 곳을 모아둘 수 있어요.
        </div>

        <div
          style={{
            marginTop: 32,
            background: colors.panel,
            borderRadius: 14,
            border: `1px solid ${colors.hairline}`,
            padding: "18px 22px",
            display: "flex",
            flexDirection: "column",
            gap: 6,
          }}
          aria-live="polite"
        >
          <div
            style={{
              fontSize: 12,
              color: colors.inkFaint,
              letterSpacing: 0.4,
            }}
          >
            그룹
          </div>
          <div
            style={{
              fontFamily: fonts.emo,
              fontSize: 20,
              fontWeight: 700,
              color: colors.ink,
              wordBreak: "break-all",
            }}
          >
            {preview.groupName}
          </div>
          <div
            style={{
              marginTop: 4,
              fontFamily: fonts.mono,
              fontSize: 12,
              color: colors.inkSoft,
            }}
          >
            {remainingText}
          </div>
        </div>

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
            onClick={onAccept}
            disabled={submitting}
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
    </div>
  );
}
