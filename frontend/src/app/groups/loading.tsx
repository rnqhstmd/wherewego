import { colors } from "@/lib/design/tokens";

/**
 * /groups 로딩 스켈레톤 (Next.js loading.tsx).
 * 카드 3개의 회색 박스로 자리 표시.
 */
export default function GroupsLoading() {
  return (
    <div
      style={{
        minHeight: "100vh",
        background: colors.bg,
        padding: 40,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 12,
      }}
    >
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          aria-hidden="true"
          style={{
            width: 380,
            maxWidth: "90%",
            height: 64,
            background: colors.hairline,
            borderRadius: 14,
            opacity: 0.6,
          }}
        />
      ))}
    </div>
  );
}
