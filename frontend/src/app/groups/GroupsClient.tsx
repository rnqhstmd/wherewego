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
 * Screen 1 — 그룹 선택.
 *
 * - 상단 바: 워드마크 + 아바타 + 닉네임.
 * - 그룹 카드: 메인 영역(클릭 → /map 또는 알림 권한) + 하단 액션 행(초대 링크).
 * - 점선 카드(새 그룹 만들기): 활성 그룹 보유 시 disabled — 1인 1활성 그룹 정책 (BR-1).
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

  const hasActiveGroup = groups.length > 0;

  const onClickCreate = () => {
    if (hasActiveGroup) {
      setToast("이미 그룹에 참여 중이에요. 그룹을 나간 후 만들 수 있어요.");
      return;
    }
    router.push("/groups/new");
  };

  const onClickInvite = () => {
    router.push("/groups/invite");
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
        <button
          type="button"
          onClick={() => router.push("/settings")}
          aria-label="설정"
          style={{
            display: "flex",
            alignItems: "center",
            gap: 10,
            background: "transparent",
            border: "none",
            padding: 0,
            cursor: "pointer",
            fontFamily: "inherit",
          }}
        >
          <div
            aria-hidden="true"
            style={{
              width: 32,
              height: 32,
              borderRadius: "50%",
              background: `linear-gradient(135deg, ${colors.pinMemory}, ${colors.pinWish})`,
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
        </button>
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
            <div
              key={g.groupId}
              style={{
                background: colors.panel,
                borderRadius: 14,
                border: `1px solid ${colors.hairline}`,
                boxShadow: `0 2px 8px ${colors.shadow}`,
                overflow: "hidden",
              }}
            >
              <button
                type="button"
                onClick={onClickGroup}
                style={{
                  width: "100%",
                  background: "transparent",
                  border: "none",
                  padding: "18px 22px",
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
              <button
                type="button"
                onClick={onClickInvite}
                style={{
                  width: "100%",
                  background: "transparent",
                  border: "none",
                  borderTop: `1px solid ${colors.hairline}`,
                  padding: "12px 22px",
                  cursor: "pointer",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                  textAlign: "left",
                  fontFamily: "inherit",
                  fontSize: 13,
                  fontWeight: 500,
                  color: colors.inkSoft,
                }}
              >
                <span style={{ display: "flex", alignItems: "center", gap: 6 }}>
                  <span aria-hidden="true">📨</span>
                  <span>초대 링크 보내기</span>
                </span>
                <span
                  aria-hidden="true"
                  style={{ color: colors.inkFaint, fontSize: 16 }}
                >
                  →
                </span>
              </button>
            </div>
          ))}

          {/* 새 그룹 만들기 — 1인 1활성 그룹 정책(BR-1)으로 활성 그룹 보유 시 비활성화 */}
          <button
            type="button"
            onClick={onClickCreate}
            disabled={hasActiveGroup}
            aria-disabled={hasActiveGroup}
            title={
              hasActiveGroup
                ? "그룹은 한 번에 하나만 참여할 수 있어요"
                : undefined
            }
            style={{
              background: "transparent",
              borderRadius: 14,
              border: `1.5px dashed ${colors.hairline}`,
              padding: "16px 22px",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 6,
              cursor: hasActiveGroup ? "not-allowed" : "pointer",
              color: colors.ctaSub,
              fontFamily: "inherit",
              opacity: hasActiveGroup ? 0.4 : 1,
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
          {hasActiveGroup ? (
            <div
              style={{
                marginTop: -4,
                textAlign: "center",
                fontSize: 11,
                color: colors.inkFaint,
                lineHeight: 1.5,
              }}
            >
              그룹은 한 번에 하나만 참여할 수 있어요.
              <br />
              다른 그룹을 만들려면 먼저 현재 그룹에서 나가야 해요.
            </div>
          ) : null}
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
