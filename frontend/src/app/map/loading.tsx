import { colors, fonts } from "@/lib/design/tokens";

/**
 * /map 라우트 로딩 UI (FR-SYS-1 Splash).
 *
 * 서버 렌더링이 끝나기 전에 표시되는 라우트 레벨 로딩. tokens 컬러/폰트로 풀스크린.
 */
export default function MapLoading() {
  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        background: colors.bg,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <div
        style={{
          fontFamily: fonts.emo,
          fontSize: 48,
          fontWeight: 700,
          color: colors.ink,
          marginBottom: 16,
          letterSpacing: -1,
        }}
      >
        우리가 갈 지도
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            style={{
              width: 8,
              height: 8,
              borderRadius: "50%",
              background: colors.cta,
              animation: `splash-dot 1.4s ease-in-out ${i * 0.16}s infinite`,
            }}
          />
        ))}
      </div>
      <style>{`
        @keyframes splash-dot {
          0%, 80%, 100% { opacity: 0.2; transform: scale(0.8); }
          40% { opacity: 1; transform: scale(1); }
        }
      `}</style>
    </div>
  );
}
