"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { BackButton } from "@/components/ui/BackButton";
import { issueInviteLink } from "@/lib/api/group-client";
import { colors, fonts } from "@/lib/design/tokens";

interface InviteLinkClientProps {
  groupId: number;
}

/**
 * 초대 링크 발급 + 공유 화면.
 *
 * - 진입 시 자동으로 `issueInviteLink(groupId)` 호출 (StrictMode 중복 방지).
 * - 응답 token을 `${origin}/onboarding/invite-code?token=${token}`로 구성.
 * - "복사" 버튼은 navigator.clipboard.writeText → 2초간 "복사됨" 표시.
 * - 만료까지 남은 시간을 1초 간격으로 카운트다운한다.
 */
export function InviteLinkClient({ groupId }: InviteLinkClientProps) {
  const router = useRouter();
  const [token, setToken] = useState<string | null>(null);
  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [now, setNow] = useState<number>(() => Date.now());
  const ranRef = useRef(false);

  // 1초 간격 시간 갱신 (카운트다운용)
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  // 진입 시 1회 발급
  useEffect(() => {
    if (ranRef.current) return;
    ranRef.current = true;
    void (async () => {
      try {
        const res = await issueInviteLink(groupId);
        setToken(res.token);
        setShareUrl(res.shareUrl);
        setExpiresAt(res.expiresAt);
      } catch (e) {
        const message =
          e instanceof Error && e.message
            ? e.message
            : "초대 링크를 만들지 못했어요. 잠시 후 다시 시도해 주세요.";
        setError(message);
      } finally {
        setLoading(false);
      }
    })();
  }, [groupId]);

  const inviteUrl = useMemo(() => {
    if (shareUrl) return shareUrl;
    // 백엔드 응답이 shareUrl 을 채우지 않은 예외 케이스의 안전한 폴백.
    if (!token || typeof window === "undefined") return "";
    return `${window.location.origin}/onboarding/invite-code?token=${encodeURIComponent(token)}`;
  }, [shareUrl, token]);

  const remainingText = useMemo(() => {
    if (!expiresAt) return "";
    const diff = new Date(expiresAt).getTime() - now;
    if (Number.isNaN(diff) || diff <= 0) return "만료됨";
    const totalMin = Math.floor(diff / 60000);
    const days = Math.floor(totalMin / (60 * 24));
    const hours = Math.floor((totalMin % (60 * 24)) / 60);
    const minutes = totalMin % 60;
    if (days > 0) {
      return `${days}일 ${hours}시간 남음`;
    }
    if (hours > 0) {
      return `${hours}시간 ${minutes}분 남음`;
    }
    const seconds = Math.floor((diff % 60000) / 1000);
    return `${minutes}분 ${seconds}초 남음`;
  }, [expiresAt, now]);

  const onCopy = async () => {
    if (!inviteUrl) return;
    try {
      if (
        typeof navigator !== "undefined" &&
        navigator.clipboard &&
        typeof navigator.clipboard.writeText === "function"
      ) {
        await navigator.clipboard.writeText(inviteUrl);
      } else {
        // 폴백: 클립보드 API가 없을 때 textarea 임시 사용
        const ta = document.createElement("textarea");
        ta.value = inviteUrl;
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
        애인을 초대해요
      </div>
      <div
        style={{
          marginTop: 12,
          fontSize: 14,
          color: colors.inkSoft,
          lineHeight: 1.6,
        }}
      >
        이 링크를 보내면 함께 지도를 만들 수 있어요
      </div>

      {/* URL panel */}
      <div
        style={{
          marginTop: 32,
          background: colors.panel,
          borderRadius: 14,
          border: `1px solid ${colors.hairline}`,
          padding: "18px 22px",
          fontFamily: fonts.mono,
          fontSize: 13,
          color: colors.ink,
          wordBreak: "break-all",
          minHeight: 80,
          display: "flex",
          alignItems: "center",
          boxShadow: `0 2px 8px ${colors.shadow}`,
        }}
        aria-live="polite"
      >
        {loading ? (
          <span style={{ color: colors.inkFaint, fontFamily: fonts.sans }}>
            초대 링크를 만들고 있어요...
          </span>
        ) : error && !inviteUrl ? (
          <span style={{ color: colors.cta, fontFamily: fonts.sans }}>
            {error}
          </span>
        ) : (
          inviteUrl
        )}
      </div>

      {/* Copy button */}
      <BtnPrimary
        onClick={onCopy}
        disabled={!inviteUrl || loading}
        style={{
          marginTop: 14,
          width: "100%",
          padding: "13px 0",
          fontSize: 15,
        }}
      >
        {copied ? "복사됨" : "복사"}
      </BtnPrimary>

      {/* Expiry note */}
      <div
        style={{
          marginTop: 18,
          display: "flex",
          flexDirection: "column",
          gap: 4,
          alignItems: "flex-start",
        }}
      >
        <div
          style={{
            fontSize: 13,
            color: colors.inkFaint,
          }}
        >
          7일 동안 유효
        </div>
        {expiresAt ? (
          <div
            style={{
              fontFamily: fonts.mono,
              fontSize: 13,
              color: colors.inkSoft,
            }}
          >
            {remainingText}
          </div>
        ) : null}
      </div>

      {error && inviteUrl ? (
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

      <BtnSub
        onClick={() => router.back()}
        style={{
          width: "100%",
          padding: "13px 0",
          fontSize: 14,
        }}
      >
        확인
      </BtnSub>
      </div>
    </div>
  );
}
