"use client";

import { IconBack } from "@/components/icons";
import { colors } from "@/lib/design/tokens";

interface BackButtonProps {
  onClick: () => void;
  ariaLabel?: string;
}

/**
 * 서브메뉴(한 단계 들어간 화면)의 좌상단 ← 버튼.
 * 닉네임 스타일 큰 제목 위에 작게 배치된다.
 */
export function BackButton({ onClick, ariaLabel = "뒤로" }: BackButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={ariaLabel}
      style={{
        width: 36,
        height: 36,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: "transparent",
        border: "none",
        cursor: "pointer",
        color: colors.inkSoft,
        padding: 0,
        marginLeft: -8,
        marginBottom: 16,
      }}
    >
      <IconBack size={22} />
    </button>
  );
}
