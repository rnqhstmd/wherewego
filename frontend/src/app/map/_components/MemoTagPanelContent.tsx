"use client";

import { useEffect, useState, useTransition } from "react";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { PinTag } from "@/components/ui/PinTag";
import { PanelLabel } from "@/components/ui/PanelLabel";
import { HLine } from "@/components/ui/HLine";
import { Input } from "@/components/ui/Input";
import { colors, fonts } from "@/lib/design/tokens";
import type { PinSummaryResponse, PinTag as PinTagType } from "@/lib/api/types";
import { createPinAction } from "../actions";
import { reverseGeocode } from "../_lib/reverseGeocode";
import type { NewPinOrigin } from "./types";

interface MemoTagPanelContentProps {
  origin: NewPinOrigin;
  groupId: number;
  mapboxToken: string;
  onCancel: () => void;
  onSuccess: (pin: PinSummaryResponse) => void;
}

/**
 * 메모/태그 입력 + 저장 패널.
 *
 * - origin.editable === true (Crosshair 진입) 인 경우 장소 이름 입력 필드 노출.
 * - origin.editable === false (검색 진입) 인 경우 placeName 고정, 표시만.
 * - 태그 필수, 메모는 선택. ApiError 코드별 한국어 메시지 매핑.
 */
export default function MemoTagPanelContent({
  origin,
  groupId,
  mapboxToken,
  onCancel,
  onSuccess,
}: MemoTagPanelContentProps) {
  const [tag, setTag] = useState<PinTagType | null>(null);
  const [memo, setMemo] = useState("");
  const [placeName, setPlaceName] = useState(origin.placeName);
  const [instagramUrl, setInstagramUrl] = useState("");
  const [urlError, setUrlError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  // editable(Crosshair) 진입 시 좌표를 주소로 표시 — reverseGeocode 비동기 조회.
  // 로딩 중에는 좌표 fallback. 실패 시도 좌표 fallback. 호출은 mount 1회 + 좌표 변경 시.
  const [resolvedAddress, setResolvedAddress] = useState<string | null>(null);
  useEffect(() => {
    if (!origin.editable) return;
    const ac = new AbortController();
    (async () => {
      try {
        const result = await reverseGeocode(
          origin.longitude,
          origin.latitude,
          mapboxToken,
          ac.signal,
        );
        if (ac.signal.aborted) return;
        setResolvedAddress(result.address ?? result.placeName ?? null);
      } catch {
        // 실패 시 좌표 fallback — setResolvedAddress 미호출
      }
    })();
    return () => ac.abort();
  }, [origin.editable, origin.latitude, origin.longitude, mapboxToken]);

  // 항상 사용자가 입력한(또는 초기값 그대로의) placeName을 사용 — 검색 진입에서도 편집 허용.
  const effectivePlaceName = placeName.trim() || origin.placeName || "(이름 없음)";
  const canSubmit =
    !!tag && effectivePlaceName.length > 0 && !pending && urlError === null;

  const validateUrl = (value: string): string | null => {
    const trimmed = value.trim();
    if (trimmed.length === 0) return null;
    if (!trimmed.startsWith("https://")) return "올바른 URL 형식이 아닙니다";
    return null;
  };

  const handleInstagramUrlChange = (value: string) => {
    setInstagramUrl(value);
    setUrlError(validateUrl(value));
  };

  const handleSave = () => {
    if (!tag) return;
    if (placeName.trim().length === 0) {
      setError("장소 이름을 입력해주세요");
      return;
    }
    startTransition(async () => {
      const result = await createPinAction(groupId, {
        placeName: effectivePlaceName,
        // 검색 진입(editable=false)은 origin.address 사용, Crosshair 진입은 reverseGeocode 결과 사용.
        // 두 케이스 모두 PinPopup 상세 조회에서 주소가 노출되도록 보장.
        address: origin.address ?? resolvedAddress,
        latitude: origin.latitude,
        longitude: origin.longitude,
        instagramUrl: instagramUrl.trim() || null,
        memo: memo.trim() || null,
        tag,
      });
      if (result.ok) {
        onSuccess(result.data);
      } else if (result.code === "PLC_DUPLICATE_PIN") {
        setError("이미 등록된 장소예요");
      } else if (result.code === "GROUP_NOT_MEMBER") {
        setError("그룹의 활성 멤버만 핀을 추가할 수 있어요");
      } else if (result.code === "PIN_PLACE_NAME_INVALID") {
        setError("장소 이름은 1~200자여야 합니다");
      } else if (result.code === "PIN_MEMO_TOO_LONG") {
        setError("메모는 500자까지 입력할 수 있어요");
      } else if (result.code === "PIN_INSTAGRAM_URL_INVALID") {
        setError(
          "인스타그램 URL이 올바르지 않습니다. https://www.instagram.com/... 형식으로 입력해주세요.",
        );
      } else {
        setError(result.message);
      }
    });
  };

  // 좌표 위치 표시: editable(Crosshair) 시 reverseGeocode 결과 주소 우선, 실패·로딩 시 좌표 fallback.
  // 검색 진입(editable=false) 시 placeName + address 결합.
  const locationLabel = origin.editable
    ? (resolvedAddress
        ?? `${origin.latitude.toFixed(6)}, ${origin.longitude.toFixed(6)}`)
    : `${origin.placeName}${origin.address ? ` · ${origin.address}` : ""}`;

  return (
    <div>
      {/* SidePanel 헤더에 이미 "새 핀 추가" 표시되므로 본문 중복 제목 제거 */}
      <PanelLabel>주소</PanelLabel>
      <div
        style={{
          fontFamily: origin.editable && resolvedAddress ? fonts.sans : fonts.mono,
          fontSize: 13,
          color: colors.inkSoft,
          marginBottom: 16,
        }}
      >
        📍 {locationLabel}
      </div>
      <HLine style={{ marginBottom: 14 }} />

      <PanelLabel>장소 이름</PanelLabel>
      <Input
        placeholder="예: 우리집"
        value={placeName}
        onChange={setPlaceName}
        style={{ marginBottom: 16 }}
      />

      <PanelLabel>태그</PanelLabel>
      <div style={{ display: "flex", gap: 8, marginBottom: 16 }}>
        <PinTag
          type="MEMORY"
          active={tag === "MEMORY"}
          onClick={() => setTag("MEMORY")}
        />
        <PinTag
          type="WISH"
          active={tag === "WISH"}
          onClick={() => setTag("WISH")}
        />
        <PinTag
          type="REEL"
          active={tag === "REEL"}
          onClick={() => setTag("REEL")}
        />
      </div>

      <PanelLabel>메모 (선택)</PanelLabel>
      <textarea
        value={memo}
        onChange={(e) => setMemo(e.target.value)}
        placeholder="메모를 입력해 보세요..."
        maxLength={500}
        style={{
          width: "100%",
          boxSizing: "border-box",
          border: `1.5px solid ${colors.hairline}`,
          borderRadius: 10,
          padding: "12px 14px",
          minHeight: 72,
          background: colors.bg,
          fontFamily: fonts.sans,
          fontSize: 14,
          color: colors.ink,
          marginBottom: 16,
          // Sheet 내부 스크롤(maxHeight 모드)과 충돌하지 않도록 사용자 리사이즈 핸들을 비활성화.
          // 모바일 환경에서는 리사이즈 핸들이 UX 가치가 낮고 데스크탑 SidePanel 에서도 동일 정책.
          resize: "none",
          outline: "none",
        }}
      />

      <PanelLabel>릴스 링크 (선택)</PanelLabel>
      <Input
        placeholder="https://instagram.com/..."
        value={instagramUrl}
        onChange={handleInstagramUrlChange}
        style={{ marginBottom: urlError ? 6 : 16 }}
      />
      {urlError && (
        <div
          style={{
            fontFamily: fonts.sans,
            fontSize: 12,
            color: colors.pinNew,
            marginBottom: 16,
          }}
        >
          {urlError}
        </div>
      )}

      {error && (
        <div
          style={{
            padding: "10px 12px",
            marginBottom: 12,
            background: `${colors.pinNew}15`,
            color: colors.pinNew,
            borderRadius: 8,
            fontSize: 13,
          }}
        >
          {error}
        </div>
      )}

      <div style={{ display: "flex", gap: 8 }}>
        <BtnSub
          onClick={onCancel}
          style={{ flex: 1, padding: "11px 0" }}
          disabled={pending}
        >
          취소
        </BtnSub>
        <BtnPrimary
          onClick={handleSave}
          disabled={!canSubmit}
          style={{ flex: 1, padding: "11px 0" }}
        >
          {pending ? "저장 중..." : "저장"}
        </BtnPrimary>
      </div>
    </div>
  );
}
