"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { BtnSub } from "@/components/ui/BtnSub";
import { PanelLabel } from "@/components/ui/PanelLabel";
import { BackButton } from "@/components/ui/BackButton";
import { IconBack } from "@/components/icons";
import type { UserResponse } from "@/lib/api/auth";
import { postLogout } from "@/lib/api/auth";
import { leaveGroup } from "@/lib/api/group-client";
import type { OnboardingStatusResponse } from "@/lib/api/me-client";
import type { ActiveGroupResponse } from "@/lib/api/types";
import { colors, fonts } from "@/lib/design/tokens";

interface SettingsClientProps {
  user: UserResponse;
  activeGroup: ActiveGroupResponse | null;
  /** Phase 11 PR-C: 챗봇/초대 항목 강등 표시용 사용자 진입 상태. */
  onboardingStatus: OnboardingStatusResponse;
}

/**
 * 사용자/그룹 설정 화면.
 *
 * 섹션:
 *  1. 사용자 (아바타 + 닉네임 + 닉네임 수정)
 *  2. 활성 그룹 (그룹명/N명/그룹 탈퇴) — 활성 그룹 보유 시에만 노출
 *  3. 챗봇 연동 (코드 발급 진입)
 *  4. 계정 (로그아웃)
 *
 * 친구 초대는 그룹 컨텍스트가 명확한 /groups 화면에서 처리한다.
 */
export function SettingsClient({ user, activeGroup, onboardingStatus }: SettingsClientProps) {
  const router = useRouter();
  const [busy, setBusy] = useState<"leave" | "logout" | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Phase 11 PR-C AC-8: 정원 도달 시 초대 행은 숨기고, 봇 매핑 있으면 ✅ 강등 표시.
  const groupIsFull = onboardingStatus.activeGroupMemberCount >= 2;
  const botLinked = onboardingStatus.hasBotMapping;

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
    }
    try {
      // 서비스 자체 게이트(maygo-gate) 쿠키도 같이 해제 — 다음 접근 시 ID/PW 재입력.
      await fetch("/api/auth/gate", { method: "DELETE" });
    } catch {
      /* 무시 — middleware가 어차피 다음 접근 때 /gate로 강제 */
    }
    router.replace("/gate");
    router.refresh();
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
          padding: "80px 32px 40px",
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
          마이페이지
        </div>
        <div
          style={{
            marginTop: 12,
            fontSize: 14,
            color: colors.inkSoft,
            lineHeight: 1.6,
          }}
        >
          계정과 그룹을 관리할 수 있어요
        </div>

        {/* Body */}
        <div
          style={{
            marginTop: 32,
            display: "flex",
            flexDirection: "column",
            gap: 18,
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
                alignItems: "baseline",
                gap: 4,
              }}
            >
              <span
                style={{
                  fontFamily: fonts.emo,
                  fontSize: 18,
                  fontWeight: 700,
                  color: colors.ink,
                  letterSpacing: -0.3,
                }}
              >
                {user.nickname}
              </span>
              <span
                style={{
                  fontFamily: fonts.sans,
                  fontSize: 12,
                  fontWeight: 500,
                  color: colors.inkSoft,
                }}
              >
                님
              </span>
            </div>
            <Row
              label="닉네임 수정"
              onClick={() => router.push("/settings/nickname")}
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
              {groupIsFull ? null : (
                <Row
                  label="📨 초대 링크 보내기"
                  onClick={() => router.push("/groups/invite")}
                  style={{ marginTop: 14 }}
                />
              )}
              <Row
                label="그룹 탈퇴"
                onClick={onLeaveGroup}
                danger
                disabled={busy !== null}
                style={groupIsFull ? { marginTop: 14 } : undefined}
              />
            </div>
          </section>
        ) : null}

        {/* 3) 챗봇 연동 */}
        <section>
          <PanelLabel>챗봇 연동</PanelLabel>
          {botLinked ? (
            // 연동 완료 상태 — 정보 텍스트 + 작은 "재발급" 링크로 강등 (AC-8).
            <div
              style={{
                background: colors.panel,
                borderRadius: 14,
                border: `1px solid ${colors.hairline}`,
                padding: "16px 22px",
                boxShadow: `0 2px 8px ${colors.shadow}`,
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 12,
              }}
            >
              <span
                style={{
                  fontSize: 14,
                  fontWeight: 500,
                  color: colors.ink,
                }}
              >
                ✅ 챗봇 연동됨
              </span>
              <button
                type="button"
                onClick={() => router.push("/bot/connect")}
                style={{
                  background: "transparent",
                  border: "none",
                  color: colors.inkFaint,
                  fontSize: 12,
                  textDecoration: "underline",
                  cursor: "pointer",
                  padding: 0,
                }}
              >
                재발급
              </button>
            </div>
          ) : (
            <>
              <div
                style={{
                  fontSize: 12,
                  color: colors.inkSoft,
                  lineHeight: 1.5,
                  margin: "4px 4px 8px",
                }}
              >
                카카오톡 챗봇에 인스타 릴스 링크를 보내면 자동으로 핀이 등록돼요.
                6자리 코드를 발급받아 챗봇에 한 번 입력하면 내 계정과 연결됩니다.
              </div>
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
            </>
          )}
        </section>

        {/* 4) 계정 */}
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
