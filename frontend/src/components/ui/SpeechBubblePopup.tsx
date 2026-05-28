"use client";

import type { CSSProperties, MouseEvent, ReactNode } from "react";
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
  collapseBody = false,
  className,
  style,
}: SpeechBubblePopupProps) {
  const lines = (memo || "").split("\n");
  const hasPlace = place !== null && place !== undefined && place !== "";
  // 모바일(<=480px)에서는 폰트/패딩/너비를 축소하여 지도 화면을 덜 가린다.
  const isCompact = useMediaQuery("(max-width: 480px)");

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
            {/* Memo */}
            <div
              style={{
                fontSize: memoFontSize,
                fontWeight: 500,
                color: colors.ink,
                lineHeight: memoLineHeight,
                letterSpacing: -0.2,
              }}
            >
              {lines.map((line, i) => (
                <div key={i}>{line}</div>
              ))}
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

        {/* 인라인 펼침 영역 (태그 칩 등) */}
        {footerContent ? (
          <div
            style={{
              marginTop: 10,
              paddingTop: 10,
              borderTop: `1px solid ${colors.hairline}`,
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
