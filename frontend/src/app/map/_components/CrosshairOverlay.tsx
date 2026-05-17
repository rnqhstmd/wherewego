import { colors } from "@/lib/design/tokens";

/**
 * 지도 정중앙에 표시되는 십자선 오버레이.
 * 핀 추가 picker 모드에서만 노출. 포인터 이벤트는 통과시킨다.
 */
export default function CrosshairOverlay() {
  return (
    <div
      style={{
        position: "absolute",
        top: "50%",
        left: "50%",
        transform: "translate(-50%, -50%)",
        pointerEvents: "none",
        zIndex: 5,
      }}
    >
      <div
        style={{
          width: 32,
          height: 32,
          borderRadius: "50%",
          border: `2px solid ${colors.cta}`,
          background: "transparent",
        }}
      />
      <div
        style={{
          position: "absolute",
          top: "50%",
          left: "50%",
          width: 2,
          height: 2,
          background: colors.cta,
          borderRadius: "50%",
          transform: "translate(-50%, -50%)",
        }}
      />
    </div>
  );
}
