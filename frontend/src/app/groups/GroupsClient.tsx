"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import type { UserResponse } from "@/lib/api/auth";
import type { ActiveGroupResponse } from "@/lib/api/types";
import { notifAsked } from "@/lib/storage/local-flags";
import { colors, fonts } from "@/lib/design/tokens";

interface GroupsClientProps {
  user: UserResponse;
  activeGroup: ActiveGroupResponse | null;
}

/**
 * Screen 1 — 그룹 선택 (screens-login.jsx::Screen1Groups 1:1).
 *
 * - 상단 바: 워드마크 + 아바타 + 닉네임.
 * - 카드 클릭: notifAsked.get() ? /map : /onboarding/notification.
 * - 점선 카드(새 그룹 만들기): 준비 중 토스트 3초.
 *   TODO: 그룹 생성 화면은 별도 PRD에서 처리.
 */
export function GroupsClient({ user, activeGroup }: GroupsClientProps) {
  const router = useRouter();
  const [toast, setToast] = useState<string | null>(null);

  const groups = useMemo<ActiveGroupResponse[]>(
    () => (activeGroup ? [activeGroup] : []),
    [activeGroup],
  );

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 3000);
    return () => clearTimeout(t);
  }, [toast]);

  const onClickGroup = () => {
    if (notifAsked.get()) {
      router.push("/map");
    } else {
      router.push("/onboarding/notification");
    }
  };

  const onClickCreate = () => {
    setToast("준비 중입니다");
  };

  return (
    <div
      style={{
        width: "100%",
        minHeight: "100vh",
        background: colors.bg,
        fontFamily: fonts.sans,
        display: "flex",
        flexDirection: "column",
      }}
    >
      {/* Top bar */}
      <div
        style={{
          height: 64,
          background: colors.panel,
          borderBottom: `1px solid ${colors.hairline}`,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "0 32px",
          flexShrink: 0,
        }}
      >
        <span
          style={{
            fontFamily: fonts.emo,
            fontSize: 22,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -0.5,
          }}
        >
          우리가 갈 지도
        </span>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <div
            aria-hidden="true"
            style={{
              width: 32,
              height: 32,
              borderRadius: "50%",
              background: `linear-gradient(135deg, ${colors.pinMemory}, ${colors.pinPlace})`,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: 16,
            }}
          >
            🙂
          </div>
          <div
            style={{
              fontSize: 13,
              fontWeight: 700,
              color: colors.ink,
            }}
          >
            {user.nickname}
          </div>
        </div>
      </div>

      {/* Center container */}
      <div
        style={{
          flex: 1,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          padding: 40,
          overflowY: "auto",
        }}
      >
        {/* Heading */}
        <div style={{ textAlign: "center" }}>
          <div
            style={{
              fontFamily: fonts.emo,
              fontSize: 30,
              fontWeight: 700,
              color: colors.ink,
              letterSpacing: -1,
            }}
          >
            어떤 지도에 들어갈까요
          </div>
          <div
            style={{
              marginTop: 10,
              fontSize: 14,
              color: colors.inkSoft,
            }}
          >
            참여 중인 그룹 {groups.length}개
          </div>
        </div>

        {/* Group cards */}
        <div
          style={{
            maxWidth: 380,
            width: "100%",
            marginTop: 32,
            display: "flex",
            flexDirection: "column",
            gap: 12,
          }}
        >
          {groups.map((g) => (
            <button
              key={g.groupId}
              type="button"
              onClick={onClickGroup}
              style={{
                background: colors.panel,
                borderRadius: 14,
                border: `1px solid ${colors.hairline}`,
                padding: "18px 22px",
                boxShadow: `0 2px 8px ${colors.shadow}`,
                cursor: "pointer",
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                textAlign: "left",
                fontFamily: "inherit",
              }}
            >
              <div>
                <div
                  style={{
                    fontFamily: fonts.emo,
                    fontSize: 18,
                    fontWeight: 700,
                    color: colors.ink,
                    letterSpacing: -0.3,
                  }}
                >
                  {g.name}
                </div>
                <div
                  style={{
                    marginTop: 4,
                    fontSize: 12,
                    color: colors.inkSoft,
                    display: "flex",
                    alignItems: "center",
                    gap: 5,
                  }}
                >
                  <span aria-hidden="true">👥</span>
                  <span>{g.memberCount}명 참여 중</span>
                </div>
              </div>
              <span
                aria-hidden="true"
                style={{ color: colors.inkSoft, fontSize: 18 }}
              >
                →
              </span>
            </button>
          ))}

          {/* New group (placeholder — 별도 PRD) */}
          <button
            type="button"
            onClick={onClickCreate}
            style={{
              background: "transparent",
              borderRadius: 14,
              border: `1.5px dashed ${colors.hairline}`,
              padding: "16px 22px",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 6,
              cursor: "pointer",
              color: colors.ctaSub,
              fontFamily: "inherit",
            }}
          >
            <span style={{ fontSize: 18 }} aria-hidden="true">
              ＋
            </span>
            <span
              style={{
                fontFamily: fonts.sans,
                fontSize: 14,
                fontWeight: 500,
              }}
            >
              새 그룹 만들기
            </span>
          </button>
        </div>
      </div>

      {/* Toast */}
      {toast ? (
        <div
          role="status"
          style={{
            position: "fixed",
            bottom: 32,
            left: "50%",
            transform: "translateX(-50%)",
            background: colors.ink,
            color: colors.panel,
            padding: "12px 20px",
            borderRadius: 8,
            fontSize: 14,
            zIndex: 100,
          }}
        >
          {toast}
        </div>
      ) : null}
    </div>
  );
}
