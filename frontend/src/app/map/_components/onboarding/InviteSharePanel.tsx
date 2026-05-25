"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { issueInviteLink } from "@/lib/api/group-client";
import { colors, fonts } from "@/lib/design/tokens";

interface InviteSharePanelProps {
  groupId: number;
  onClose: () => void;
}

/**
 * /map 화면 위에 띄우는 인라인 초대 링크 모달 (Open Decision #5 권장안).
 *
 * - 진입 시 자동으로 issueInviteLink(groupId) 호출.
 * - 응답에 shareUrl 이 있으면 단축 URL, 없으면 `${origin}/onboarding/invite-code?token=...` 폴백.
 * - "복사" 버튼 + 외부 클릭 시 onClose.
 */
export function InviteSharePanel({ groupId, onClose }: InviteSharePanelProps) {
  const [token, setToken] = useState<string | null>(null);
  const [shareUrlFromApi, setShareUrlFromApi] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const ranRef = useRef(false);

  useEffect(() => {
    if (ranRef.current) return;
    ranRef.current = true;
    void (async () => {
      try {
        const res = (await issueInviteLink(groupId)) as {
          token: string;
          expiresAt: string;
          shareUrl?: string;
        };
        setToken(res.token);
        setShareUrlFromApi(res.shareUrl ?? null);
      } catch (e) {
        setError(
          e instanceof Error && e.message
            ? e.message
            : "초대 링크를 만들지 못했어요.",
        );
      } finally {
        setLoading(false);
      }
    })();
  }, [groupId]);

  const shareUrl = useMemo(() => {
    if (shareUrlFromApi) return shareUrlFromApi;
    if (!token || typeof window === "undefined") return "";
    return `${window.location.origin}/onboarding/invite-code?token=${encodeURIComponent(token)}`;
  }, [shareUrlFromApi, token]);

  const onCopy = async () => {
    if (!shareUrl) return;
    try {
      if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(shareUrl);
      } else {
        const ta = document.createElement("textarea");
        ta.value = shareUrl;
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
      role="dialog"
      aria-modal="true"
      aria-label="초대 링크 공유"
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(0,0,0,0.4)",
        zIndex: 60,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 24,
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 380,
          background: colors.panel,
          borderRadius: 16,
          border: `1px solid ${colors.hairline}`,
          padding: "22px 24px",
          fontFamily: fonts.sans,
          boxShadow: `0 8px 32px ${colors.shadow}`,
          display: "flex",
          flexDirection: "column",
          gap: 12,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <div
            style={{
              fontFamily: fonts.emo,
              fontSize: 18,
              fontWeight: 700,
              color: colors.ink,
            }}
          >
            짝꿍에게 보내요
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
              fontSize: 18,
              lineHeight: 1,
            }}
          >
            ✕
          </button>
        </div>
        <div
          style={{
            background: colors.bg,
            borderRadius: 10,
            padding: "14px 16px",
            fontFamily: fonts.mono,
            fontSize: 12,
            color: colors.ink,
            wordBreak: "break-all",
            minHeight: 60,
            display: "flex",
            alignItems: "center",
          }}
          aria-live="polite"
        >
          {loading ? (
            <span style={{ color: colors.inkFaint, fontFamily: fonts.sans }}>
              초대 링크를 만들고 있어요...
            </span>
          ) : error ? (
            <span style={{ color: colors.cta, fontFamily: fonts.sans }}>
              {error}
            </span>
          ) : (
            shareUrl
          )}
        </div>
        <button
          type="button"
          onClick={onCopy}
          disabled={!shareUrl || loading}
          style={{
            width: "100%",
            background: colors.cta,
            color: "#fff",
            border: "none",
            borderRadius: 10,
            padding: "12px 0",
            fontSize: 14,
            fontWeight: 700,
            cursor: shareUrl && !loading ? "pointer" : "not-allowed",
            opacity: shareUrl && !loading ? 1 : 0.6,
          }}
        >
          {copied ? "복사됨" : "링크 복사"}
        </button>
        <div
          style={{
            fontSize: 11,
            color: colors.inkFaint,
            textAlign: "center",
          }}
        >
          7일 동안 유효해요.
        </div>
      </div>
    </div>
  );
}
