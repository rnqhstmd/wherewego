"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { apiFetch } from "@/lib/api/http-client";
import { colors, fonts } from "@/lib/design/tokens";

interface Step2InviteProps {
  onCompleted: () => void;
  onSkip: () => void;
}

interface MyGroupResponse {
  groupId: number;
  name: string;
  createdAt: string;
  memberCount: number;
}

/**
 * 백엔드 응답이 PR-A 미머지 환경에서 `shareUrl`/`slug` 를 포함하지 않을 수 있다.
 * 두 경우 모두 안전하게 동작하도록 optional 타입으로 정의한다.
 */
interface InviteLinkResponseLoose {
  token: string;
  expiresAt: string;
  slug?: string;
  shareUrl?: string;
}

/**
 * 위저드 Step 2 — 초대 링크 공유.
 *
 * 진입 시 자동으로 활성 그룹 ID 조회 + 초대 링크 발급. shareUrl(PR-A 머지 후) 또는
 * `${origin}/onboarding/invite-code?token=...` 폴백 URL 을 표시.
 * "복사" / "다음에 할게요" 허용.
 */
export function Step2Invite({ onCompleted, onSkip }: Step2InviteProps) {
  const [link, setLink] = useState<InviteLinkResponseLoose | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const ranRef = useRef(false);

  useEffect(() => {
    if (ranRef.current) return;
    ranRef.current = true;
    void (async () => {
      try {
        const group = await apiFetch<MyGroupResponse | null>("/groups/me");
        if (!group) {
          setError("그룹이 없어요. 먼저 그룹을 만들어주세요.");
          return;
        }
        const issued = await apiFetch<InviteLinkResponseLoose>(
          `/groups/${group.groupId}/invite-links`,
          { method: "POST" },
        );
        setLink(issued);
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
  }, []);

  const shareUrl = useMemo(() => {
    if (!link) return "";
    if (link.shareUrl) return link.shareUrl;
    if (typeof window === "undefined") return "";
    return `${window.location.origin}/onboarding/invite-code?token=${encodeURIComponent(link.token)}`;
  }, [link]);

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
        짝꿍에게 링크를 보내요
      </div>
      <div
        style={{
          marginTop: 10,
          fontSize: 14,
          color: colors.inkSoft,
          lineHeight: 1.6,
        }}
      >
        이 링크를 받은 사람이 합류하면 함께 지도를 만들 수 있어요.
      </div>

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
        }}
        aria-live="polite"
      >
        {loading ? (
          <span style={{ color: colors.inkFaint, fontFamily: fonts.sans }}>
            초대 링크를 만들고 있어요...
          </span>
        ) : error ? (
          <span style={{ color: colors.cta, fontFamily: fonts.sans }}>{error}</span>
        ) : (
          shareUrl
        )}
      </div>

      <div style={{ flex: 1 }} />

      <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <BtnPrimary
          onClick={onCopy}
          disabled={!shareUrl || loading}
          style={{ width: "100%", padding: "14px 0", fontSize: 15 }}
        >
          {copied ? "복사됨" : "링크 복사"}
        </BtnPrimary>
        <BtnSub
          onClick={onCompleted}
          style={{ width: "100%", padding: "13px 0", fontSize: 14 }}
        >
          다음 단계
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
