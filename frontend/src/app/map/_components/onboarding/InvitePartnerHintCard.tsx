"use client";

import { useState } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import { snoozeHint } from "../../_lib/hintSnooze";
import { InviteSharePanel } from "./InviteSharePanel";

interface InvitePartnerHintCardProps {
  groupId: number;
  onDismiss: () => void;
}

/**
 * /map 좌상단 발견성 카드 — 활성 그룹 멤버가 1명일 때 "짝꿍 초대" 안내.
 *
 * - "초대 링크 받기" CTA → InviteSharePanel 인라인 모달 (Open Decision #5 권장안).
 * - "×" 닫기 → 3일 snooze + 부모에 onDismiss 통지.
 * - 카드 자체는 부모(MapClient)가 isHintSnoozed/조건 검사로 노출 여부 결정.
 */
export function InvitePartnerHintCard({
  groupId,
  onDismiss,
}: InvitePartnerHintCardProps) {
  const [openShare, setOpenShare] = useState(false);

  const onClose = () => {
    snoozeHint("invite-partner");
    onDismiss();
  };

  return (
    <>
      <div
        role="region"
        aria-label="짝꿍 초대 안내"
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
            혼자 사용 중이에요
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
          짝꿍을 초대하면 함께 갈 곳을 모을 수 있어요.
        </div>
        <button
          type="button"
          onClick={() => setOpenShare(true)}
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
          초대 링크 받기
        </button>
      </div>
      {openShare ? (
        <InviteSharePanel groupId={groupId} onClose={() => setOpenShare(false)} />
      ) : null}
    </>
  );
}
