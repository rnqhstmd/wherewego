"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type JSX,
} from "react";
import type { PinSummaryResponse } from "@/lib/api/types";
import { colors, fonts } from "@/lib/design/tokens";
import {
  isCanvasSupported,
  renderPinCard,
} from "@/lib/share/renderPinCard";
import { sanitizeFilename } from "@/lib/share/sanitizeFilename";

interface PinShareSheetProps {
  pin: PinSummaryResponse;
  mapboxToken: string;
  mapboxStyleUrl: string | null;
  onClose: () => void;
}

type Phase =
  | { kind: "loading" }
  | { kind: "ready"; blob: Blob; previewUrl: string }
  | { kind: "unsupported" }
  | { kind: "error" };

type InlineNotice =
  | { kind: "copy-success" }
  | { kind: "copy-failed" }
  | { kind: "save-success" }
  | null;

/**
 * Phase 9 핀 공유 카드 바텀시트(모달).
 *
 * 마운트 즉시 `renderPinCard`로 1080×1350 PNG를 생성한 뒤 4:5 미리보기 + 두 액션
 * (복사하기 / 이미지 저장)을 노출한다. 결과는 시트 하단 인라인 안내로 3초 노출 후 자동 소거.
 *
 * - BR-7 Canvas 미지원 → phase `unsupported`, 두 버튼 비활성 + 안내
 * - BR-8 Clipboard 미지원 → notice `copy-failed`, 시트 유지하여 이미지 저장 경로 안내
 * - BR-6 Mapbox 실패는 renderPinCard 내부에서 단색 폴백 처리되므로 본 시트에서는 별도 분기 없음
 */
export default function PinShareSheet({
  pin,
  mapboxToken,
  mapboxStyleUrl,
  onClose,
}: PinShareSheetProps): JSX.Element {
  // BR-1: 카드의 작성자 라벨은 닉네임 null → "익명". 팝업의 라벨(`사용자 #N` 등)과 별개로 카드 표기를 위해 직접 계산.
  const cardNickname = pin.createdByNickname ?? "익명";
  // BR-7 Canvas 미지원 분기는 lazy state init으로 처리 — useEffect 내 setState 회피
  const [phase, setPhase] = useState<Phase>(() =>
    isCanvasSupported() ? { kind: "loading" } : { kind: "unsupported" },
  );
  const [notice, setNotice] = useState<InlineNotice>(null);
  const [justCopied, setJustCopied] = useState(false);
  // mount slide-up 전환: 초기 false → 다음 프레임에서 true로 토글
  const [enter, setEnter] = useState(false);

  // PinPopup 109L 패턴 재사용 — BR-10 YYYY.MM.DD 포맷
  const formattedDate = new Date(pin.createdAt)
    .toLocaleDateString("ko-KR", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    })
    .replace(/\s/g, "")
    .replace(/\.$/, "");

  // cleanup에서 최신 phase의 previewUrl을 revoke하기 위한 ref(closure 캡쳐 회피)
  const phaseRef = useRef<Phase>(phase);
  useEffect(() => {
    phaseRef.current = phase;
  }, [phase]);

  // mount slide-up 전환: 초기 translateY(100%) → 다음 프레임 translateY(0)
  useEffect(() => {
    const raf = window.requestAnimationFrame(() => {
      setEnter(true);
    });
    return () => window.cancelAnimationFrame(raf);
  }, []);

  // 카드 렌더 useEffect
  useEffect(() => {
    // Canvas 미지원: 초기 lazy state init이 이미 "unsupported"로 설정 — 추가 작업 없음
    if (!isCanvasSupported()) return;

    // AC-3 정량 측정 (dev only) — mount 시점 mark
    if (process.env.NODE_ENV !== "production") {
      try {
        performance.mark("pin-share-card:render-start");
      } catch {
        // performance.mark 미지원 환경 무시
      }
    }

    const ac = new AbortController();
    let cancelled = false;
    (async () => {
      try {
        const result = await renderPinCard(
          {
            pin,
            authorLabel: cardNickname,
            formattedDate,
            mapboxToken,
            mapboxStyleUrl,
          },
          ac.signal,
        );
        if (cancelled) {
          // 마운트 해제 후 결과가 도착한 경우 즉시 revoke하여 누수 차단
          URL.revokeObjectURL(result.previewDataUrl);
          return;
        }
        setPhase({
          kind: "ready",
          blob: result.blob,
          previewUrl: result.previewDataUrl,
        });
        // AC-3 정량 측정 (dev only) — ready 전환 직후 mark + measure
        if (process.env.NODE_ENV !== "production") {
          try {
            performance.mark("pin-share-card:render-ready");
            const measure = performance.measure(
              "pin-share-card:render-duration",
              "pin-share-card:render-start",
              "pin-share-card:render-ready",
            );
            console.info("[Phase 9 card render]", {
              pinId: pin.id,
              durationMs: measure.duration,
            });
          } catch {
            // performance.measure 미지원 환경 무시
          }
        }
      } catch (err) {
        if (cancelled) return;
        if (err instanceof Error && err.message === "CANVAS_UNSUPPORTED") {
          setPhase({ kind: "unsupported" });
          return;
        }
        // AbortError는 cleanup에서 의도된 케이스 — 사용자 노출 불필요
        if (err instanceof DOMException && err.name === "AbortError") {
          return;
        }
        setPhase({ kind: "error" });
      }
    })();

    return () => {
      cancelled = true;
      ac.abort();
      const latest = phaseRef.current;
      if (latest.kind === "ready") {
        URL.revokeObjectURL(latest.previewUrl);
      }
    };
    // pin 객체 전체 대신 id로 안정화
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pin.id, mapboxToken, mapboxStyleUrl]);

  // notice 자동 소거 (3초)
  useEffect(() => {
    if (notice === null) return;
    const t = window.setTimeout(() => setNotice(null), 3000);
    return () => window.clearTimeout(t);
  }, [notice]);

  // body 스크롤 락
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  // ESC 닫기
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [onClose]);

  const handleCopy = useCallback(async (blob: Blob) => {
    // SSR 환경에서도 ReferenceError 회피를 위해 typeof + globalThis 사용.
    if (
      typeof navigator === "undefined" ||
      !navigator.clipboard ||
      typeof globalThis.ClipboardItem === "undefined"
    ) {
      setNotice({ kind: "copy-failed" });
      return;
    }
    try {
      await navigator.clipboard.write([
        new ClipboardItem({ "image/png": blob }),
      ]);
      setJustCopied(true);
      setNotice({ kind: "copy-success" });
      window.setTimeout(() => setJustCopied(false), 2000);
    } catch {
      setNotice({ kind: "copy-failed" });
    }
  }, []);

  const handleDownload = useCallback((blob: Blob, placeName: string) => {
    const safe = sanitizeFilename(placeName);
    const filename = `우리가갈지도_${safe}.png`;
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    // Safari 등 일부 환경에서 다운로드 처리가 비동기로 큐에 들어가는 경우를 위해 1초 지연 후 revoke
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    setNotice({ kind: "save-success" });
  }, []);

  const isReady = phase.kind === "ready";
  const primaryBg = isReady ? colors.ink : colors.hairline;
  const primaryColor = isReady ? "#ffffff" : colors.inkSoft;
  const secondaryColor = isReady ? colors.ink : colors.inkSoft;
  const cursor = isReady ? "pointer" : "not-allowed";

  return (
    <>
      {/* backdrop */}
      <div
        role="presentation"
        onClick={onClose}
        style={{
          position: "fixed",
          inset: 0,
          background: "rgba(26, 26, 46, 0.55)",
          opacity: enter ? 1 : 0,
          transition: "opacity 200ms cubic-bezier(0.16, 1, 0.3, 1)",
          zIndex: 50,
        }}
      />
      {/* sheet */}
      <div
        role="dialog"
        aria-modal="true"
        aria-label="핀 공유 카드"
        style={{
          // 화면 가운데 중앙 정렬 모달 — 모바일·데스크탑 공통.
          // 진입 transition은 opacity + translateY 미세 슬라이드(시각 부드러움)로 보강.
          position: "fixed",
          top: "50%",
          left: "50%",
          transform: enter
            ? "translate(-50%, -50%)"
            : "translate(-50%, calc(-50% + 12px))",
          opacity: enter ? 1 : 0,
          transition:
            "transform 200ms cubic-bezier(0.16, 1, 0.3, 1), opacity 200ms cubic-bezier(0.16, 1, 0.3, 1)",
          width: "calc(100% - 32px)",
          maxWidth: 420,
          maxHeight: "calc(100vh - 48px)",
          overflowY: "auto",
          background: colors.panel,
          borderRadius: 20,
          padding: 20,
          paddingBottom: 24,
          zIndex: 51,
          boxShadow: "0 24px 60px rgba(0,0,0,0.22)",
          fontFamily: fonts.sans,
          boxSizing: "border-box",
        }}
      >
        {/* 헤더: 타이틀 + 닫기 X */}
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            marginBottom: 16,
          }}
        >
          <strong
            style={{
              fontSize: 16,
              fontWeight: 700,
              color: colors.ink,
            }}
          >
            공유 카드
          </strong>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            style={{
              background: "transparent",
              border: 0,
              fontSize: 20,
              cursor: "pointer",
              padding: 4,
              color: colors.inkSoft,
              lineHeight: 1,
            }}
          >
            ×
          </button>
        </div>

        {/* 미리보기 */}
        <div
          style={{
            width: "100%",
            display: "flex",
            justifyContent: "center",
            marginBottom: 16,
          }}
        >
          <div
            style={{
              aspectRatio: "4 / 5",
              width: "min(100%, 320px)",
              background: colors.mapBg,
              borderRadius: 12,
              overflow: "hidden",
              position: "relative",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            {phase.kind === "loading" && (
              <div
                style={{
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "center",
                  gap: 12,
                  padding: 16,
                }}
              >
                <svg
                  width="28"
                  height="28"
                  viewBox="0 0 28 28"
                  style={{ animation: "spin 0.8s linear infinite" }}
                  aria-label="로딩 중"
                  role="img"
                >
                  <circle
                    cx="14"
                    cy="14"
                    r="11"
                    stroke={colors.inkSoft}
                    strokeWidth="2.5"
                    strokeLinecap="round"
                    strokeDasharray="40 80"
                    fill="none"
                  />
                </svg>
                <div
                  style={{
                    fontSize: 13,
                    color: colors.inkSoft,
                    textAlign: "center",
                  }}
                >
                  카드를 만들고 있어요…
                </div>
              </div>
            )}
            {phase.kind === "ready" && (
              // 사용자 자체 데이터로 만든 미리보기이므로 next/image 대신 native img 사용
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={phase.previewUrl}
                alt="핀 공유 카드 미리보기"
                style={{
                  width: "100%",
                  height: "100%",
                  objectFit: "cover",
                  display: "block",
                }}
              />
            )}
            {phase.kind === "unsupported" && (
              <div
                style={{
                  fontSize: 13,
                  color: colors.inkSoft,
                  textAlign: "center",
                  padding: 16,
                }}
              >
                이 기기에서는 이미지 생성이 지원되지 않아요
              </div>
            )}
            {phase.kind === "error" && (
              <div
                style={{
                  fontSize: 13,
                  color: colors.inkSoft,
                  textAlign: "center",
                  padding: 16,
                }}
              >
                카드를 만들지 못했어요
              </div>
            )}
          </div>
        </div>

        {/* 두 액션 버튼 */}
        <div style={{ display: "flex", gap: 8 }}>
          <button
            type="button"
            onClick={() => {
              if (phase.kind === "ready") handleCopy(phase.blob);
            }}
            disabled={!isReady}
            style={{
              flex: 1,
              padding: "12px 0",
              borderRadius: 10,
              border: 0,
              background: primaryBg,
              color: primaryColor,
              fontSize: 14,
              fontWeight: 600,
              cursor,
              fontFamily: fonts.sans,
            }}
          >
            {justCopied ? "복사됨 ✓" : "복사하기"}
          </button>
          <button
            type="button"
            onClick={() => {
              if (phase.kind === "ready") {
                handleDownload(phase.blob, pin.placeName);
              }
            }}
            disabled={!isReady}
            style={{
              flex: 1,
              padding: "12px 0",
              borderRadius: 10,
              border: `1px solid ${colors.hairline}`,
              background: "transparent",
              color: secondaryColor,
              fontSize: 14,
              fontWeight: 600,
              cursor,
              fontFamily: fonts.sans,
            }}
          >
            이미지 저장
          </button>
        </div>

        {/* 인라인 안내 */}
        {notice !== null && (
          <div
            role="status"
            aria-live="polite"
            style={{
              marginTop: 12,
              padding: "8px 12px",
              background: colors.bg,
              borderRadius: 8,
              fontSize: 12,
              color: colors.inkSoft,
              textAlign: "center",
              border: `1px solid ${colors.hairline}`,
            }}
          >
            {notice.kind === "copy-success" && "이미지가 복사되었어요"}
            {notice.kind === "copy-failed" &&
              "이 브라우저는 이미지 복사를 지원하지 않아요. 이미지 저장을 이용해주세요"}
            {notice.kind === "save-success" && "이미지를 저장했어요"}
          </div>
        )}
      </div>
    </>
  );
}
