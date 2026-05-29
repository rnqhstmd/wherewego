"use client";

import type { CSSProperties, MouseEvent, ReactNode } from "react";
import { useLayoutEffect, useRef } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import { IconMenuVert } from "@/components/icons";
import { useMediaQuery } from "@/lib/hooks/useMediaQuery";
import { PinDot, type PinDotType } from "./PinDot";

interface SpeechBubblePopupProps {
  /** 핀의 화면 좌표 (px 또는 % 문자열) */
  pinX: number | string;
  pinY: number | string;
  memo: string;
  place?: string | null;
  addr: string;
  author: string;
  date: string;
  pinType: PinDotType;
  /** 인스타그램 릴스 URL — 있으면 주소 아래 "릴스 보기 ↗" 링크 노출 */
  instagramUrl?: string | null;
  width?: number;
  /** ⋮ 메뉴 클릭 콜백 (인라인 태그 칩 펼침 토글) */
  onMenuClick?: (event: MouseEvent<HTMLButtonElement>) => void;
  /** ⋮ 버튼 좌측 sibling 영역 (Phase 9 공유 버튼 등). undefined면 ⋮ 단독 렌더. */
  shareAction?: ReactNode;
  /**
   * 본문 우측에 placeRow와 같은 라인에 떠 있는 액션 영역 (선택).
   * place가 있으면 place 줄과 같은 row, 없으면 addr 줄과 같은 row 우측에 정렬된다.
   */
  bodyAction?: ReactNode;
  /** ⋮ 영역 아래 인라인 펼침 영역 (PinTag 칩 등) */
  footerContent?: ReactNode;
  /** 본문 하단 추가 children (커스텀 확장) */
  children?: ReactNode;
  /**
   * 메모 우측에 떠 있는 썸네일 노드 (Phase 13 FR-PIN-11a, ~44px 원형 권장).
   * undefined/null 이면 메모 블록은 기존 단독 레이아웃을 그대로 유지한다(AC-11).
   * 썸네일 img 자체와 클릭→뷰어 로직은 호출처(PinPopup)가 주입한다.
   */
  memoThumbnail?: ReactNode;
  /**
   * 메모 영역을 제자리에서 대체하는 펼친 사진 노드 (메모 ↔ 사진 전환).
   * undefined/null 이면 전환 비활성 — 항상 메모를 렌더한다.
   */
  expandedPhoto?: ReactNode;
  /**
   * true면 메모+memoThumbnail 행 대신 expandedPhoto 를 표시한다.
   * 전환은 높이 애니메이션 + 크로스페이드(prefers-reduced-motion 시 즉시 전환).
   */
  showExpandedPhoto?: boolean;
  /**
   * true면 메모/장소/주소 영역을 숨기고 footerContent에만 집중.
   * 수정 모드 등에서 원본 값과 입력값이 동시에 보여 헷갈리는 것을 방지.
   */
  collapseBody?: boolean;
  className?: string;
  style?: CSSProperties;
}

/**
 * Pin detail popup — tokens.jsx::SpeechBubblePopup 1:1 변환 + 인라인 태그 칩 펼침 지원.
 *
 * 두 패턴:
 *  - place 있음: ● 가게이름 / 주소 줄(왼쪽 여백)
 *  - place 없음: ● + 주소 한 줄
 *
 * 좌표 계산은 호출자(MapClient) 책임. 이 컴포넌트는 position:absolute + 좌표만 받는다.
 * ⋮ 클릭 시 onMenuClick 콜백을 발사하고, footerContent 가 있으면 하단에 인라인 펼침.
 */
export function SpeechBubblePopup({
  pinX,
  pinY,
  memo,
  place,
  addr,
  author,
  date,
  pinType,
  instagramUrl,
  width = 296,
  onMenuClick,
  shareAction,
  bodyAction,
  footerContent,
  children,
  memoThumbnail,
  expandedPhoto,
  showExpandedPhoto = false,
  collapseBody = false,
  className,
  style,
}: SpeechBubblePopupProps) {
  const lines = (memo || "").split("\n");
  const hasPlace = place !== null && place !== undefined && place !== "";
  // 모바일(<=480px)에서는 폰트/패딩/너비를 축소하여 지도 화면을 덜 가린다.
  const isCompact = useMediaQuery("(max-width: 480px)");
  // 접근성: 모션 최소화 환경에서는 메모↔사진 전환 애니메이션을 생략(즉시 전환).
  const reduceMotion = useMediaQuery("(prefers-reduced-motion: reduce)");
  // 사진 노드가 주입되고 showExpandedPhoto=true 일 때만 사진을 펼친다.
  const photoActive = showExpandedPhoto && expandedPhoto != null;

  // ─── 메모↔사진 height FLIP 애니메이션 ────────────────────────────
  // active 노드는 normal flow 라 래퍼 auto 높이가 항상 올바르다(측정 실패/초기 렌더 안전).
  // 전환 직전 자연 높이(prevHeightRef)를 시작점으로, 새 자연 높이를 끝점으로 명령형 height
  // 애니메이션을 발화한 뒤 height 를 auto 로 복귀시킨다(이후 콘텐츠 변화에 자연 적응).
  const swapWrapRef = useRef<HTMLDivElement | null>(null);
  const prevPhotoActiveRef = useRef(photoActive);
  // 평상시(전환 아님) 자연 높이를 지속 보관 → 전환 시점에 "직전 높이"로 사용.
  const prevHeightRef = useRef<number>(0);

  useLayoutEffect(() => {
    const el = swapWrapRef.current;
    if (!el) return;

    const wasActive = prevPhotoActiveRef.current;
    prevPhotoActiveRef.current = photoActive;

    // 전환 비활성(reduceMotion) 또는 photoActive 변화 없음 → height 명령형 제어 없이
    // 직전 자연 높이만 갱신해 둔다(다음 전환의 시작점).
    if (reduceMotion || wasActive === photoActive) {
      el.style.height = "";
      prevHeightRef.current = el.offsetHeight;
      return;
    }

    // photoActive 가 바뀐 프레임: 렌더는 이미 새 active 노드를 반영했으므로
    // el.offsetHeight 가 새 자연 높이(newH). 시작점은 직전 보관 높이(oldH).
    const oldH = prevHeightRef.current;
    const newH = el.offsetHeight;
    prevHeightRef.current = newH;

    // jsdom 등 레이아웃 미구현 환경: 높이가 0 또는 동일 → 애니메이션 의미 없음, no-op.
    if (oldH === 0 || newH === 0 || oldH === newH) {
      el.style.height = "";
      return;
    }

    // FLIP: 시작 높이 고정 → 강제 reflow → 다음 프레임에 끝 높이로 transition 발화.
    el.style.height = `${oldH}px`;
    void el.offsetHeight; // force reflow
    let timeoutId = 0;
    const raf = requestAnimationFrame(() => {
      const node = swapWrapRef.current;
      if (!node) return;
      node.style.height = `${newH}px`;
    });

    const cleanup = () => {
      const node = swapWrapRef.current;
      if (node) node.style.height = ""; // auto 복귀
    };
    const onEnd = (e: TransitionEvent) => {
      if (e.target !== el || e.propertyName !== "height") return;
      cleanup();
      el.removeEventListener("transitionend", onEnd);
      if (timeoutId) clearTimeout(timeoutId);
    };
    el.addEventListener("transitionend", onEnd);
    // transitionend 미발생(jsdom / 중단) 대비 fallback (~320ms).
    timeoutId = window.setTimeout(() => {
      cleanup();
      el.removeEventListener("transitionend", onEnd);
    }, 320);

    return () => {
      cancelAnimationFrame(raf);
      if (timeoutId) clearTimeout(timeoutId);
      el.removeEventListener("transitionend", onEnd);
    };
  }, [photoActive, reduceMotion]);

  const effectiveWidth = isCompact ? Math.min(width, 240) : width;
  const containerPadding = isCompact ? "12px 14px 10px" : "16px 18px 14px";
  const containerRadius = isCompact ? 14 : 18;
  const memoFontSize = isCompact ? 13 : 15;
  const memoLineHeight = isCompact ? 1.45 : 1.5;
  const sectionGap = isCompact ? 10 : 12;
  const placeFontSize = isCompact ? 12.5 : 13.5;
  const placeRowGap = isCompact ? 6 : 7;
  const placeDotSizePlace = isCompact ? 7 : 8;
  const placeDotSizeMemory = isCompact ? 10 : 11;
  const addrFontSize = isCompact ? 10.5 : 11.5;
  const addrIndent = hasPlace ? (isCompact ? 16 : 18) : 0;
  const bottomRowMarginTop = collapseBody ? 0 : isCompact ? 10 : 12;
  const bottomRowPaddingTop = collapseBody ? 0 : isCompact ? 8 : 10;
  const dateFontSize = isCompact ? 11 : 12;
  const writtenByFontSize = isCompact ? 10 : 11;
  const menuBtnSize = isCompact ? 24 : 28;
  const menuIconSize = isCompact ? 14 : 16;
  const instagramFontSize = isCompact ? 11 : 12;

  return (
    <div
      className={className}
      style={{
        position: "absolute",
        left: pinX,
        top: pinY,
        transform: `translate(-50%, calc(-100% - ${isCompact ? 12 : 16}px))`,
        zIndex: 22,
        ...style,
      }}
    >
      <div
        style={{
          width: effectiveWidth,
          background: colors.panel,
          borderRadius: containerRadius,
          boxShadow: `0 10px 28px ${colors.shadowMd}, 0 0 0 1px ${colors.hairline}`,
          padding: containerPadding,
          fontFamily: fonts.sans,
          position: "relative",
        }}
      >
        {!collapseBody && (
          <>
            {/* Memo ↔ 사진 제자리 전환 (Phase 13 후속).
                expandedPhoto 가 없으면 메모 행만, 있으면 두 노드를 겹쳐 두고
                활성 노드를 normal flow(컨테이너 높이 결정)·비활성 노드를 absolute 로 두어
                높이 transition + opacity 크로스페이드를 동시에 낸다. */}
            <div
              ref={swapWrapRef}
              style={{
                position: "relative",
                overflow: "hidden",
                transition: reduceMotion ? "none" : "height 0.3s ease",
              }}
            >
              {/* 메모 행 (+ 우상단 코너에 absolute 로 떠 있는 정사각 썸네일 slot).
                  작업 2: 썸네일을 flex 흐름에서 빼 absolute 로 띄워 메모/장소/날짜가
                  사진 유무와 무관하게 동일 위치(normal flow)를 유지한다. 메모 텍스트는
                  썸네일과 겹치지 않게 우측 패딩을 줘 첫 줄을 띄운다. */}
              <div
                style={{
                  position: "relative",
                  ...(photoActive
                    ? {
                        position: "absolute",
                        inset: 0,
                        pointerEvents: "none",
                      }
                    : {}),
                  opacity: photoActive ? 0 : 1,
                  transition: reduceMotion ? "none" : "opacity 0.25s ease",
                }}
                aria-hidden={photoActive}
              >
                <div
                  style={{
                    minWidth: 0,
                    fontSize: memoFontSize,
                    fontWeight: 500,
                    color: colors.ink,
                    lineHeight: memoLineHeight,
                    letterSpacing: -0.2,
                    // 작업 1: 코너 썸네일(36px)과 첫 줄이 겹치지 않게 우측 패딩만 확보.
                    // minHeight 는 두지 않아 한 줄 메모 시 메모 영역 높이가 사진 없는 핀과
                    // 동일하다(36px 썸네일은 코너 absolute 라 흐름을 밀지 않고, 메모 아래
                    // sectionGap 여유 안에서 장소 행을 침범하지 않는다).
                    paddingRight: memoThumbnail ? 44 : 0,
                    // 사진 있을 때: 다중 줄 메모에서 썸네일과 세로 정렬 맞춤(좌상단 붙음 방지).
                    ...(memoThumbnail
                      ? {
                          display: "flex",
                          flexDirection: "column",
                          justifyContent: "center",
                        }
                      : {}),
                  }}
                >
                  {lines.map((line, i) => (
                    <div key={i}>{line}</div>
                  ))}
                </div>
                {memoThumbnail ? (
                  <div
                    style={{
                      position: "absolute",
                      top: 0,
                      right: 0,
                      pointerEvents: photoActive ? "none" : "auto",
                    }}
                  >
                    {memoThumbnail}
                  </div>
                ) : null}
              </div>

              {/* 펼친 사진 노드 */}
              {expandedPhoto != null ? (
                <div
                  style={{
                    ...(photoActive
                      ? {}
                      : {
                          position: "absolute",
                          inset: 0,
                          pointerEvents: "none",
                        }),
                    opacity: photoActive ? 1 : 0,
                    transition: reduceMotion ? "none" : "opacity 0.3s ease",
                  }}
                  aria-hidden={!photoActive}
                >
                  {expandedPhoto}
                </div>
              ) : null}
            </div>

            {/* Place + address */}
            <div style={{ marginTop: sectionGap }}>
              {hasPlace ? (
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: placeRowGap,
                    marginBottom: 3,
                  }}
                >
                  <PinDot
                    type={pinType}
                    size={
                      pinType === "memory" ? placeDotSizeMemory : placeDotSizePlace
                    }
                  />
                  <span
                    style={{
                      fontSize: placeFontSize,
                      fontWeight: 700,
                      color: colors.ink,
                      letterSpacing: -0.2,
                      flex: 1,
                      minWidth: 0,
                    }}
                  >
                    {place}
                  </span>
                  {bodyAction ? (
                    <span style={{ display: "inline-flex", alignItems: "center", flexShrink: 0 }}>
                      {bodyAction}
                    </span>
                  ) : null}
                </div>
              ) : null}
              <div
                style={{
                  fontFamily: fonts.mono,
                  fontSize: addrFontSize,
                  color: colors.inkSoft,
                  letterSpacing: -0.1,
                  paddingLeft: addrIndent,
                  display: "flex",
                  alignItems: "center",
                  gap: 6,
                }}
              >
                {!hasPlace ? (
                  <PinDot
                    type={pinType}
                    size={
                      pinType === "memory" ? placeDotSizeMemory : placeDotSizePlace
                    }
                  />
                ) : null}
                <span style={{ flex: 1, minWidth: 0 }}>{addr}</span>
                {!hasPlace && bodyAction ? (
                  <span style={{ display: "inline-flex", alignItems: "center", flexShrink: 0 }}>
                    {bodyAction}
                  </span>
                ) : null}
              </div>
              {instagramUrl ? (
                <a
                  href={instagramUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    gap: 4,
                    marginTop: 8,
                    marginLeft: addrIndent,
                    fontFamily: fonts.sans,
                    fontSize: instagramFontSize,
                    fontWeight: 600,
                    color: "#C13584",
                    textDecoration: "none",
                  }}
                >
                  <span>📷</span>
                  <span>릴스 보기</span>
                  <span style={{ fontSize: 10 }}>↗</span>
                </a>
              ) : null}
            </div>
          </>
        )}

        {/* Bottom row: date + author + ⋮ */}
        <div
          style={{
            marginTop: bottomRowMarginTop,
            paddingTop: bottomRowPaddingTop,
            borderTop: collapseBody ? "none" : `1px solid ${colors.hairline}`,
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <div
            style={{
              fontFamily: fonts.mono,
              fontSize: dateFontSize,
              color: colors.inkSoft,
              fontStyle: "italic",
            }}
          >
            {date}&nbsp;&nbsp;
            {/* "written by {author}"는 한 묶음 — 좁은 모바일 화면에서 author 중간 줄바꿈 방지 */}
            <span style={{ whiteSpace: "nowrap" }}>
              <span
                style={{
                  fontFamily: fonts.sans,
                  fontStyle: "italic",
                  color: colors.inkSoft,
                  fontWeight: 400,
                  fontSize: writtenByFontSize,
                  marginRight: 6,
                }}
              >
                written by
              </span>
              <span
                style={{
                  fontFamily: fonts.sans,
                  fontStyle: "normal",
                  color: colors.ink,
                  fontWeight: 600,
                }}
              >
                {author}
              </span>
            </span>
          </div>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 4,
            }}
          >
            {shareAction}
            <button
              type="button"
              onClick={onMenuClick}
              aria-label="더 보기"
              style={{
                width: menuBtnSize,
                height: menuBtnSize,
                borderRadius: 6,
                background: "transparent",
                border: "none",
                cursor: "pointer",
                color: colors.inkSoft,
                padding: 0,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <IconMenuVert size={menuIconSize} color={colors.inkSoft} />
            </button>
          </div>
        </div>

        {/* 인라인 펼침 영역 (태그 칩 / 수정 폼 등).
            수정 모드에서 사진 미리보기까지 들어가면 말풍선이 화면 위로 넘칠 만큼 길어지므로
            max-height + 세로 스크롤로 가둬 팝업 자체가 과도하게 커지지 않게 한다(짧은 내용은 스크롤 없음). */}
        {footerContent ? (
          <div
            style={{
              marginTop: 10,
              paddingTop: 10,
              borderTop: `1px solid ${colors.hairline}`,
              maxHeight: "min(50vh, 340px)",
              overflowY: "auto",
            }}
          >
            {footerContent}
          </div>
        ) : null}

        {children}

        {/* Bubble tail */}
        <svg
          width="22"
          height="12"
          viewBox="0 0 22 12"
          style={{
            position: "absolute",
            bottom: -11,
            left: "50%",
            transform: "translateX(-50%)",
          }}
          aria-hidden="true"
        >
          <path
            d="M 0 0 L 11 11 L 22 0 Z"
            fill={colors.panel}
            stroke={colors.hairline}
            strokeWidth="1"
          />
          <path
            d="M 0.5 0 L 21.5 0"
            stroke={colors.panel}
            strokeWidth="2"
          />
        </svg>
      </div>
    </div>
  );
}
