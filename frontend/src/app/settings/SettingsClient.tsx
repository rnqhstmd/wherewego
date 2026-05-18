"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { BtnSub } from "@/components/ui/BtnSub";
import { PanelLabel } from "@/components/ui/PanelLabel";
import { IconBack } from "@/components/icons";
import type { UserResponse } from "@/lib/api/auth";
import { postLogout } from "@/lib/api/auth";
import { leaveGroup } from "@/lib/api/group-client";
import type { ActiveGroupResponse } from "@/lib/api/types";
import { colors, fonts } from "@/lib/design/tokens";

interface SettingsClientProps {
  user: UserResponse;
  activeGroup: ActiveGroupResponse | null;
}

/**
 * 사용자/그룹 설정 화면.
 *
 * 섹션:
 *  1. 사용자 (아바타 + 닉네임 + 닉네임 수정)
 *  2. 활성 그룹 (그룹명/N명/그룹 탈퇴) — 활성 그룹 보유 시에만 노출
 *  3. 챗봇 연동 (코드 발급 진입)
 *  4. 친구 초대 (초대 링크 진입) — 활성 그룹 보유 시에만 노출
 *  5. 계정 (로그아웃)
 */
export function SettingsClient({ user, activeGroup }: SettingsClientProps) {
  const router = useRouter();
  const [busy, setBusy] = useState<"leave" | "logout" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const onLeaveGroup = async () => {
    if (!activeGroup || busy) return;
    const ok =
      typeof window !== "undefined" &&
      window.confirm(
        `'${activeGroup.name}' 그룹에서 정말 나가시겠어요?\n이 그룹의 핀에 더 이상 접근할 수 없어요.`,
      );
    if (!ok) return;
    setBusy("leave");
    setError(null);
    try {
      await leaveGroup(activeGroup.groupId);
      router.replace("/onboarding/group-start");
      router.refresh();
    } catch (e) {
      const message =
        e instanceof Error && e.message
          ? e.message
          : "그룹 탈퇴에 실패했어요. 잠시 후 다시 시도해 주세요.";
      setError(message);
      setBusy(null);
    }
  };

  const onLogout = async () => {
    if (busy) return;
    setBusy("logout");
    setError(null);
    try {
      await postLogout();
    } catch {
      // 백엔드 호출 실패해도 클라이언트 측에서 로그인 화면으로 이동시키는 정책.
      // 쿠키가 만료되지 않더라도 다음 보호 페이지 진입 시 가드가 재처리.
    } finally {
      router.replace("/login");
      router.refresh();
    }
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
          height: 56,
          background: colors.panel,
          borderBottom: `1px solid ${colors.hairline}`,
          display: "flex",
          alignItems: "center",
          padding: "0 16px",
          flexShrink: 0,
          gap: 8,
        }}
      >
        <button
          type="button"
          onClick={() => router.back()}
          aria-label="뒤로"
          style={{
            width: 36,
            height: 36,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            background: "transparent",
            border: "none",
            cursor: "pointer",
            color: colors.ink,
            padding: 0,
          }}
        >
          <IconBack size={22} />
        </button>
        <span
          style={{
            fontFamily: fonts.emo,
            fontSize: 22,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -0.5,
          }}
        >
          설정
        </span>
      </div>

      {/* Body */}
      <div
        style={{
          flex: 1,
          padding: "20px 20px 40px",
          display: "flex",
          flexDirection: "column",
          gap: 18,
          overflowY: "auto",
        }}
      >
        {/* 1) 사용자 */}
        <section>
          <PanelLabel>사용자</PanelLabel>
          <div
            style={{
              background: colors.panel,
              borderRadius: 14,
              border: `1px solid ${colors.hairline}`,
              padding: "18px 22px",
              boxShadow: `0 2px 8px ${colors.shadow}`,
            }}
          >
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 12,
              }}
            >
              <div
                aria-hidden="true"
                style={{
                  width: 44,
                  height: 44,
                  borderRadius: "50%",
                  background: `linear-gradient(135deg, ${colors.pinMemory}, ${colors.pinPlace})`,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  fontSize: 20,
                  flexShrink: 0,
                }}
              >
                🙂
              </div>
              <div
                style={{
                  fontFamily: fonts.emo,
                  fontSize: 18,
                  fontWeight: 700,
                  color: colors.ink,
                  letterSpacing: -0.3,
                }}
              >
                {user.nickname}
              </div>
            </div>
            <Row
              label="닉네임 수정"
              onClick={() => router.push("/onboarding/nickname")}
              style={{ marginTop: 14 }}
            />
          </div>
        </section>

        {/* 2) 활성 그룹 */}
        {activeGroup ? (
          <section>
            <PanelLabel>활성 그룹</PanelLabel>
            <div
              style={{
                background: colors.panel,
                borderRadius: 14,
                border: `1px solid ${colors.hairline}`,
                padding: "18px 22px",
                boxShadow: `0 2px 8px ${colors.shadow}`,
              }}
            >
              <div
                style={{
                  fontFamily: fonts.emo,
                  fontSize: 18,
                  fontWeight: 700,
                  color: colors.ink,
                  letterSpacing: -0.3,
                }}
              >
                {activeGroup.name}
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
                <span>{activeGroup.memberCount}명 참여 중</span>
              </div>
              <Row
                label="그룹 탈퇴"
                onClick={onLeaveGroup}
                danger
                disabled={busy !== null}
                style={{ marginTop: 14 }}
              />
            </div>
          </section>
        ) : null}

        {/* 3) 챗봇 연동 */}
        <section>
          <PanelLabel>챗봇 연동</PanelLabel>
          <div
            style={{
              background: colors.panel,
              borderRadius: 14,
              border: `1px solid ${colors.hairline}`,
              padding: "6px 22px",
              boxShadow: `0 2px 8px ${colors.shadow}`,
            }}
          >
            <Row
              label="챗봇 연동 코드 발급"
              onClick={() => router.push("/bot/connect")}
            />
          </div>
        </section>

        {/* 4) 친구 초대 */}
        {activeGroup ? (
          <section>
            <PanelLabel>친구 초대</PanelLabel>
            <div
              style={{
                background: colors.panel,
                borderRadius: 14,
                border: `1px solid ${colors.hairline}`,
                padding: "6px 22px",
                boxShadow: `0 2px 8px ${colors.shadow}`,
              }}
            >
              <Row
                label="초대 링크 보내기"
                onClick={() => router.push("/groups/invite")}
              />
            </div>
          </section>
        ) : null}

        {/* 5) 계정 */}
        <section>
          <PanelLabel>계정</PanelLabel>
          <BtnSub
            onClick={onLogout}
            disabled={busy !== null}
            style={{
              width: "100%",
              padding: "13px 0",
              fontSize: 14,
            }}
          >
            {busy === "logout" ? "로그아웃 중..." : "로그아웃"}
          </BtnSub>
        </section>

        {error ? (
          <div
            role="alert"
            style={{
              fontSize: 13,
              color: colors.cta,
              textAlign: "center",
            }}
          >
            {error}
          </div>
        ) : null}
      </div>
    </div>
  );
}

interface RowProps {
  label: string;
  onClick: () => void;
  danger?: boolean;
  disabled?: boolean;
  style?: React.CSSProperties;
}

/** 섹션 카드 내부의 단일 행. 우측 → 아이콘 표시. */
function Row({
  label,
  onClick,
  danger = false,
  disabled = false,
  style,
}: RowProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      style={{
        width: "100%",
        background: "transparent",
        border: "none",
        padding: "12px 0",
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        cursor: disabled ? "not-allowed" : "pointer",
        opacity: disabled ? 0.5 : 1,
        fontFamily: "inherit",
        textAlign: "left",
        ...style,
      }}
    >
      <span
        style={{
          fontSize: 14,
          fontWeight: 500,
          color: danger ? colors.cta : colors.ink,
        }}
      >
        {label}
      </span>
      <span
        aria-hidden="true"
        style={{
          color: colors.inkFaint,
          display: "inline-flex",
          transform: "rotate(180deg)",
        }}
      >
        <IconBack size={18} />
      </span>
    </button>
  );
}
