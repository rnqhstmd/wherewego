"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { searchPlaces } from "@/lib/api/place";
import type { PlaceSearchItem } from "@/lib/api/types";
import { colors, fonts } from "@/lib/design/tokens";
import { Input } from "@/components/ui/Input";

interface SearchPanelContentProps {
  onSelectPlace: (place: PlaceSearchItem) => void;
}

/**
 * 검색창 + 결과 목록 (Sheet/SidePanel 내부 콘텐츠).
 *
 * - 300ms 디바운스 후 `searchPlaces` 호출 (Client Fetch, 설계 §6).
 * - 결과 항목 클릭 시 onSelectPlace 콜백 → MapClient 가 MemoTag 단계로 전이.
 *
 * 동기 setState 회피를 위해 keyword 변경 시 reset은 onChange 핸들러에서 처리하고,
 * effect 본문은 setTimeout 콜백(외부 시스템) 안에서만 setState 한다.
 */
export default function SearchPanelContent({
  onSelectPlace,
}: SearchPanelContentProps) {
  const [keyword, setKeyword] = useState("");
  const [items, setItems] = useState<PlaceSearchItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleKeywordChange = useCallback((value: string) => {
    setKeyword(value);
    if (!value.trim()) {
      // 빈 입력 즉시 reset (event handler 안에서의 setState 는 허용)
      setItems([]);
      setError(null);
      setLoading(false);
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
        debounceRef.current = null;
      }
    }
  }, []);

  useEffect(() => {
    const trimmed = keyword.trim();
    if (!trimmed) return;

    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      // setTimeout 콜백 = 외부 시스템 이벤트 → setState 허용
      setLoading(true);
      searchPlaces(trimmed)
        .then((res) => {
          setItems(res.items);
          setError(null);
        })
        .catch(() => {
          setError("검색을 일시적으로 사용할 수 없어요");
          setItems([]);
        })
        .finally(() => {
          setLoading(false);
        });
    }, 300);

    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
        debounceRef.current = null;
      }
    };
  }, [keyword]);

  return (
    <div>
      <Input
        placeholder="장소 검색..."
        value={keyword}
        onChange={handleKeywordChange}
        autoFocus
        style={{ marginBottom: 14 }}
      />
      {error && (
        <div style={{ padding: "12px 0", color: colors.cta, fontSize: 13 }}>
          {error}
        </div>
      )}
      {!error && !loading && keyword.trim() && items.length === 0 && (
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
