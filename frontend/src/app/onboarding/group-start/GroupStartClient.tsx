"use client";

import { useRouter } from "next/navigation";
import { PinDot } from "@/components/ui/PinDot";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * Screen 0c — 그룹 시작 (screens-login.jsx::Screen0cGroupStart 1:1).
 *
 * - 새 그룹 만들기 → /groups/new
 * - 초대 코드로 합류 → /onboarding/invite-code
 */
export function GroupStartClient() {
  const router = useRouter();

  const onClickCreate = () => {
    router.push("/groups/new");
  };

  const onClickJoin = () => {
    router.push("/onboarding/invite-code");
  };

  return (
    <div
      style={{
        padding: "70px 28px 32px",
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
          fontSize: 28,
          fontWeight: 700,
          color: colors.ink,
          lineHeight: 1.3,
          letterSpacing: -1,
        }}
      >
        어떻게 시작할까요
      </div>
      <div
        style={{
          marginTop: 10,
          fontSize: 14,
          color: colors.inkSoft,
        }}
      >
        혼자서도, 함께서도 괜찮아요
      </div>

      {/* Option 1: Create new group */}
      <button
        type="button"
        onClick={onClickCreate}
        style={{
          marginTop: 32,
          background: colors.panel,
          borderRadius: 16,
          border: `1.5px solid ${colors.cta}`,
          padding: "20px 22px",
          cursor: "pointer",
          boxShadow: `0 4px 14px rgba(196,98,45,0.12)`,
          textAlign: "left",
          fontFamily: "inherit",
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            marginBottom: 6,
          }}
        >
          <PinDot type="wish" size={14} />
          <span
            style={{
              fontFamily: fonts.emo,
              fontSize: 17,
              fontWeight: 700,
              color: colors.ink,
            }}
          >
            새 그룹 만들기
          </span>
        </div>
        <div
          style={{
            fontSize: 13,
            color: colors.inkSoft,
            lineHeight: 1.5,
          }}
        >
          이름을 정하고 친구를 초대해서
          <br />
          함께 핀을 찍어요
        </div>
      </button>

      {/* Option 2: Join with code */}
      <button
        type="button"
        onClick={onClickJoin}
        style={{
          marginTop: 12,
          background: "transparent",
          borderRadius: 16,
          border: `1.5px solid ${colors.hairline}`,
          padding: "20px 22px",
          cursor: "pointer",
          textAlign: "left",
          fontFamily: "inherit",
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            marginBottom: 6,
          }}
        >
          <span style={{ fontSize: 16 }} aria-hidden="true">
            🔗
          </span>
          <span
            style={{
              fontFamily: fonts.emo,
              fontSize: 17,
              fontWeight: 700,
              color: colors.ink,
            }}
          >
            초대 코드로 합류
          </span>
        </div>
        <div
          style={{
            fontSize: 13,
            color: colors.inkSoft,
            lineHeight: 1.5,
          }}
        >
          받은 6자리 코드를 입력해서
          <br />
          이미 만들어진 그룹에 들어가요
        </div>
      </button>

      <div style={{ flex: 1 }} />

      <div
        style={{
          textAlign: "center",
          fontSize: 13,
          color: colors.inkFaint,
        }}
      >
        나중에 설정에서 변경할 수 있어요
      </div>
    </div>
  );
}
