"use client";

import type { ReactNode } from "react";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { colors, fonts } from "@/lib/design/tokens";

interface MobileTopNavProps {
  /** 마이페이지 아바타에 노출할 닉네임 첫 글자. (현재는 사람 아이콘으로 통일) */
  myNickname?: string;
  /** 우상단 프로필 노출 여부. 데스크탑은 사이드바 하단에 프로필을 두므로 false. */
  showProfile?: boolean;
  /**
   * 우상단 프로필 좌측에 표시할 알림 벨 노드.
   * MapClient가 useNotifications 결과로 <NotificationBell .../>를 만들어 prop으로 전달.
   * MobileTopNav는 단일 인스턴스를 위해 직접 useNotifications를 호출하지 않는다.
   */
  notificationBell?: ReactNode;
}

/**
 * 모바일 전용 상단 내비게이션 — 좌상단 원형 로고 + 우상단 원형 프로필.
 *
 * - 좌측: 작은 원 안에 핀+지구본 로고. 클릭 시 우측으로 펼쳐지며 SpeechBubble 스타일
 *         말풍선(제목 + 부제)이 등장. 외부 클릭 시 자동 접힘.
 * - 우측: 진한 톤 원형 프로필 (사람 아이콘). 지도 위에 단독으로 떠 있어도 대비를 확보.
 */
export default function MobileTopNav({
  showProfile = true,
  notificationBell,
}: MobileTopNavProps) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  // 외부 클릭 시 말풍선 닫기.
  useEffect(() => {
    if (!open) return;
    const handle = (e: MouseEvent | TouchEvent) => {
      if (!wrapRef.current) return;
      if (wrapRef.current.contains(e.target as Node)) return;
      setOpen(false);
    };
    document.addEventListener("mousedown", handle);
    document.addEventListener("touchstart", handle);
    return () => {
      document.removeEventListener("mousedown", handle);
      document.removeEventListener("touchstart", handle);
    };
  }, [open]);

  return (
    <>
      {/* 좌상단 로고 + 말풍선 */}
      <div
        ref={wrapRef}
        style={{
          position: "absolute",
          top: 14,
          left: 14,
          display: "flex",
          alignItems: "flex-start",
          gap: 10,
          zIndex: 26,
        }}
      >
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-label="우리가 갈 지도"
          aria-expanded={open}
          style={{
            width: 44,
            height: 44,
            borderRadius: "50%",
            border: `1px solid ${colors.hairline}`,
            background: colors.panel,
            boxShadow: `0 6px 18px ${colors.shadow}`,
            cursor: "pointer",
            padding: 0,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            flexShrink: 0,
          }}
        >
          <svg width={26} height={26} viewBox="0 0 24 24" aria-hidden="true" style={{ display: "block" }}>
            <circle cx="12" cy="13.5" r="7.5" fill="none" stroke={colors.ink} strokeWidth="1.2" />
            <ellipse cx="12" cy="13.5" rx="7.5" ry="3" fill="none" stroke={colors.ink} strokeWidth="1" opacity="0.55" />
            <path
              d="M12 1.5c-3 0-5.4 2.4-5.4 5.4 0 3.6 5.4 9 5.4 9s5.4-5.4 5.4-9c0-3-2.4-5.4-5.4-5.4z"
              fill={colors.pinMemory}
              stroke={colors.ink}
              strokeWidth="1.2"
              strokeLinejoin="round"
            />
            <path
              d="M12 9.3l-0.55-0.5C10.1 7.6 9 6.7 9 5.55c0-0.94 0.74-1.68 1.68-1.68 0.53 0 1.04 0.25 1.32 0.64 0.28-0.39 0.79-0.64 1.32-0.64C14.26 3.87 15 4.61 15 5.55c0 1.15-1.1 2.05-2.45 3.25L12 9.3z"
              fill="#FFFFFF"
            />
          </svg>
        </button>

        {/* 말풍선 — 옆으로 펼쳐지며 등장. SpeechBubblePopup 톤에 맞춘 카드 + 좌측 꼬리. */}
        {open && (
          <div
            role="dialog"
            aria-label="서비스 소개"
            style={{
              position: "relative",
              background: colors.panel,
              border: `1px solid ${colors.hairline}`,
              borderRadius: 16,
              boxShadow: `0 10px 28px ${colors.shadowMd}`,
              padding: "14px 18px",
              maxWidth: 240,
              animation:
                "maygo-bubble-pop 220ms cubic-bezier(0.2,0.8,0.2,1) both",
            }}
          >
            {/* 좌측 꼬리 */}
            <svg
              width="10"
              height="14"
              viewBox="0 0 10 14"
              aria-hidden="true"
              style={{
                position: "absolute",
                left: -9,
                top: 16,
                display: "block",
              }}
            >
              <path
                d="M9 0 L0 7 L9 14 Z"
                fill={colors.panel}
                stroke={colors.hairline}
                strokeWidth="1"
              />
              <path d="M9 1 L9 13" stroke={colors.panel} strokeWidth="2" />
            </svg>
            <div
              style={{
                // BR: Gowun Batang(emo)은 한글 글자별 vertical metric이 균일하지 않아
                // "지" 등이 baseline에서 시각적으로 어긋남. 부제목과 동일한 Pretendard로 통일.
                fontFamily: fonts.sans,
                fontSize: 17,
                fontWeight: 700,
                color: colors.ink,
                letterSpacing: -0.3,
                lineHeight: 1.35,
              }}
            >
              우리가 갈 지도
            </div>
            <div
              style={{
                marginTop: 4,
                fontFamily: fonts.sans,
                fontSize: 12.5,
                color: colors.inkSoft,
                lineHeight: 1.5,
                letterSpacing: -0.2,
              }}
            >
              우리의 장소를 지도 위에 아카이빙해요
            </div>
          </div>
        )}
      </div>

      {/* 우상단 [알림 벨][프로필] — 모바일 전용. 데스크탑 사이드바 하단 프로필과 동일한 담백 톤. */}
      {showProfile && (
        <div
          style={{
            position: "absolute",
            top: 14,
            right: 14,
            display: "flex",
            alignItems: "center",
            gap: 10,
            zIndex: 26,
          }}
        >
          {notificationBell}
          <Link
            href="/settings"
            aria-label="마이페이지"
            title="마이페이지"
            style={{
              width: 44,
              height: 44,
              borderRadius: "50%",
              background: colors.bg,
              border: `1px solid ${colors.hairline}`,
              color: colors.inkSoft,
              boxShadow: `0 6px 18px ${colors.shadow}`,
              textDecoration: "none",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="12" cy="9" r="3.6" fill="none" stroke="currentColor" strokeWidth="1.7" />
              <path
                d="M5.5 19.5c0-3.5 2.9-6 6.5-6s6.5 2.5 6.5 6"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.7"
                strokeLinecap="round"
              />
            </svg>
          </Link>
        </div>
      )}
    </>
  );
}
