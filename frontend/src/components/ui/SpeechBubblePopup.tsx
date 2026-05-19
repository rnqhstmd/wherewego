"use client";

import type { CSSProperties, MouseEvent, ReactNode } from "react";
import { colors, fonts } from "@/lib/design/tokens";
import { IconMenuVert } from "@/components/icons";
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
  footerContent,
  children,
  collapseBody = false,
  className,
  style,
}: SpeechBubblePopupProps) {
  const lines = (memo || "").split("\n");
  const hasPlace = place !== null && place !== undefined && place !== "";

  return (
    <div
      className={className}
      style={{
        position: "absolute",
        left: pinX,
        top: pinY,
        transform: "translate(-50%, calc(-100% - 16px))",
        zIndex: 22,
        ...style,
      }}
    >
      <div
        style={{
          width,
          background: colors.panel,
          borderRadius: 18,
          boxShadow: `0 10px 28px ${colors.shadowMd}, 0 0 0 1px ${colors.hairline}`,
          padding: "16px 18px 14px",
          fontFamily: fonts.sans,
          position: "relative",
        }}
      >
        {!collapseBody && (
          <>
            {/* Memo */}
            <div
              style={{
                fontSize: 15,
                fontWeight: 500,
                color: colors.ink,
                lineHeight: 1.5,
                letterSpacing: -0.2,
              }}
            >
              {lines.map((line, i) => (
                <div key={i}>{line}</div>
              ))}
            </div>

            {/* Place + address */}
            <div style={{ marginTop: 12 }}>
              {hasPlace ? (
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 7,
                    marginBottom: 3,
                  }}
                >
                  <PinDot type={pinType} size={pinType === "memory" ? 11 : 8} />
                  <span
                    style={{
                      fontSize: 13.5,
                      fontWeight: 700,
                      color: colors.ink,
                      letterSpacing: -0.2,
                    }}
                  >
                    {place}
                  </span>
                </div>
              ) : null}
              <div
                style={{
                  fontFamily: fonts.mono,
                  fontSize: 11.5,
                  color: colors.inkSoft,
                  letterSpacing: -0.1,
                  paddingLeft: hasPlace ? 18 : 0,
                  display: "flex",
                  alignItems: "center",
                  gap: 6,
                }}
              >
                {!hasPlace ? (
                  <PinDot type={pinType} size={pinType === "memory" ? 11 : 8} />
                ) : null}
                <span>{addr}</span>
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
                    marginLeft: hasPlace ? 18 : 0,
                    fontFamily: fonts.sans,
                    fontSize: 12,
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
            marginTop: collapseBody ? 0 : 12,
            paddingTop: collapseBody ? 0 : 10,
            borderTop: collapseBody ? "none" : `1px solid ${colors.hairline}`,
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <div
            style={{
              fontFamily: fonts.mono,
              fontSize: 12,
              color: colors.inkSoft,
              fontStyle: "italic",
            }}
          >
            {date}&nbsp;&nbsp;
            <span
              style={{
                fontFamily: fonts.sans,
                fontStyle: "italic",
                color: colors.inkSoft,
                fontWeight: 400,
                fontSize: 11,
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
          </div>
          <button
            type="button"
            onClick={onMenuClick}
            aria-label="더 보기"
            style={{
              width: 28,
              height: 28,
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
            <IconMenuVert size={16} color={colors.inkSoft} />
          </button>
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
