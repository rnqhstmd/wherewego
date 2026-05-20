"use client";

import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { searchPlaces } from "@/lib/api/place";
import type { PlaceSearchItem } from "@/lib/api/types";
import { colors, fonts } from "@/lib/design/tokens";
import { Input } from "@/components/ui/Input";
import { IconSearch } from "@/components/icons";

interface SearchPanelContentProps {
  onSelectPlace: (place: PlaceSearchItem) => void;
}

/**
 * 검색창 + 결과 목록 (Sheet/SidePanel 내부 콘텐츠).
 *
 * - 입력 중에는 API 호출 없음. Enter 또는 돋보기 버튼 클릭 시에만 `searchPlaces` 호출.
 * - 결과 항목 클릭 시 onSelectPlace 콜백 → MapClient 가 MemoTag 단계로 전이.
 */
export default function SearchPanelContent({
  onSelectPlace,
}: SearchPanelContentProps) {
  const [keyword, setKeyword] = useState("");
  const [items, setItems] = useState<PlaceSearchItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasSearched, setHasSearched] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  const handleKeywordChange = useCallback((value: string) => {
    setKeyword(value);
  }, []);

  const handleSubmit = useCallback(() => {
    const trimmed = keyword.trim();
    if (!trimmed || loading) return;

    if (abortRef.current) abortRef.current.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(null);
    setHasSearched(true);
    searchPlaces(trimmed, controller.signal)
      .then((res) => {
        setItems(res.items);
      })
      .catch((e: unknown) => {
        if (e instanceof DOMException && e.name === "AbortError") return;
        setError("검색을 일시적으로 사용할 수 없어요");
        setItems([]);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [keyword, loading]);

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLInputElement>) => {
      if (event.key === "Enter") {
        event.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit],
  );

  // 언마운트 시 in-flight 요청 cleanup.
  useEffect(
    () => () => {
      if (abortRef.current) {
        abortRef.current.abort();
        abortRef.current = null;
      }
    },
    [],
  );

  return (
    <div>
      <div style={{ position: "relative", marginBottom: 14 }}>
        <Input
          placeholder="장소 검색"
          value={keyword}
          onChange={handleKeywordChange}
          onKeyDown={handleKeyDown}
          autoFocus
          style={{ paddingRight: 38 }}
        />
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!keyword.trim() || loading}
          aria-label="검색"
          style={{
            position: "absolute",
            top: "50%",
            right: 6,
            transform: "translateY(-50%)",
            width: 28,
            height: 28,
            borderRadius: 6,
            border: "none",
            background: "transparent",
            cursor: keyword.trim() ? "pointer" : "default",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            color: keyword.trim() ? colors.cta : colors.inkFaint,
          }}
        >
          <IconSearch size={18} color="currentColor" />
        </button>
      </div>
      {loading && (
        <div style={{ padding: "12px 0", color: colors.inkSoft, fontSize: 13 }}>
          검색 중...
        </div>
      )}
      {error && (
        <div style={{ padding: "12px 0", color: colors.cta, fontSize: 13 }}>
          {error}
        </div>
      )}
      {!error && !loading && hasSearched && items.length === 0 && (
        <div
          style={{ padding: "12px 0", color: colors.inkSoft, fontSize: 13 }}
        >
          검색 결과가 없어요
        </div>
      )}
      {items.map((item, i) => (
        <button
          key={`${item.placeName}-${i}`}
          type="button"
          onClick={() => onSelectPlace(item)}
          style={{
            width: "100%",
            textAlign: "left",
            padding: "11px 0",
            borderBottom: `1px solid ${colors.hairline}`,
            background: "transparent",
            border: "none",
            borderTop: "none",
            borderLeft: "none",
            borderRight: "none",
            cursor: "pointer",
            display: "flex",
            alignItems: "flex-start",
            gap: 10,
          }}
        >
          <span style={{ fontSize: 14, marginTop: 1 }}>📍</span>
          <div style={{ flex: 1 }}>
            <div
              style={{
                fontFamily: fonts.sans,
                fontSize: 14,
                fontWeight: 600,
                color: colors.ink,
              }}
            >
              {item.placeName}
            </div>
            <div
              style={{
                fontFamily: fonts.mono,
                fontSize: 12,
                color: colors.inkSoft,
                marginTop: 2,
              }}
            >
              {item.address ?? ""}
            </div>
          </div>
        </button>
      ))}
    </div>
  );
}
