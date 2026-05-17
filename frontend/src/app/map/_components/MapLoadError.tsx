import { colors, fonts } from "@/lib/design/tokens";

export type MapLoadErrorReason =
  | "TOKEN_MISSING"
  | "STYLE"
  | "QUOTA"
  | "GENERIC";

interface MapLoadErrorProps {
  reason?: MapLoadErrorReason;
}

const MESSAGES: Record<MapLoadErrorReason, string> = {
  TOKEN_MISSING: "지도 설정이 누락됐어요. 잠시 후 다시 시도해 주세요.",
  STYLE: "지도 스타일을 불러오지 못했어요. 새로고침해 보세요.",
  QUOTA: "지도 사용량이 일시적으로 초과됐어요. 잠시 후 다시 시도해 주세요.",
  GENERIC: "지도를 불러오지 못했어요.",
};

/**
 * 지도 로드 실패 화면. 토큰 누락, 스타일 로드 실패, 쿼터 초과 등 사유 표시.
 */
export default function MapLoadError({ reason = "GENERIC" }: MapLoadErrorProps) {
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
        padding: 24,
        textAlign: "center",
      }}
    >
      <div
        style={{
          fontFamily: fonts.emo,
          fontSize: 22,
          fontWeight: 700,
          color: colors.ink,
          marginBottom: 12,
        }}
      >
        지도를 표시할 수 없어요
      </div>
      <div
        style={{
          fontFamily: fonts.sans,
          fontSize: 14,
          color: colors.inkSoft,
          maxWidth: 320,
          lineHeight: 1.5,
        }}
      >
        {MESSAGES[reason]}
      </div>
    </div>
  );
}
