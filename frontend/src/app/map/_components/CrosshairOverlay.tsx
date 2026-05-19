import { colors } from "@/lib/design/tokens";

/**
 * 지도 정중앙에 표시되는 십자선 오버레이.
 * 핀 추가 picker 모드에서만 노출. 포인터 이벤트는 통과시킨다.
 */
export default function CrosshairOverlay() {
  const size = 28;
  const arm = 10;
  const thickness = 2;
  return (
    <div
      style={{
        position: "absolute",
        top: "50%",
        left: "50%",
        transform: "translate(-50%, -50%)",
        pointerEvents: "none",
        zIndex: 5,
        width: size,
        height: size,
      }}
    >
      {/* 가로선: 중심 0~arm 양쪽 */}
      <div
        style={{
          position: "absolute",
          top: "50%",
          left: 0,
          width: arm,
          height: thickness,
          background: colors.cta,
          transform: "translateY(-50%)",
        }}
      />
      <div
        style={{
          position: "absolute",
          top: "50%",
          right: 0,
          width: arm,
          height: thickness,
          background: colors.cta,
          transform: "translateY(-50%)",
        }}
      />
      {/* 세로선 */}
      <div
        style={{
          position: "absolute",
          left: "50%",
          top: 0,
          width: thickness,
          height: arm,
          background: colors.cta,
          transform: "translateX(-50%)",
        }}
      />
      <div
        style={{
          position: "absolute",
          left: "50%",
          bottom: 0,
          width: thickness,
          height: arm,
          background: colors.cta,
          transform: "translateX(-50%)",
        }}
      />
      {/* 중앙 점 */}
      <div
        style={{
          position: "absolute",
          top: "50%",
          left: "50%",
          width: 3,
          height: 3,
          background: colors.cta,
          borderRadius: "50%",
          transform: "translate(-50%, -50%)",
        }}
      />
    </div>
  );
}
