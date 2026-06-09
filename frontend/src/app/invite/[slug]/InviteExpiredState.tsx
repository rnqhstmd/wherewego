"use client";

import { IOS_APP_URL } from "@/lib/config/appStore";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * 만료/소진/존재하지 않는 슬러그에 대한 안내 상태.
 * 정보 노출 방지를 위해 백엔드도 404 INVITE_LINK_NOT_FOUND 로 통일하므로
 * 사용자에게는 동일하게 "만료" 메시지를 보여준다.
 *
 * 웹 가입은 종료(앱 전용)되어 "홈으로"(/map) 동선이 없다.
 * 대신 앱 설치를 유도한다.
 */
export function InviteExpiredState() {
  return (
    <div
      style={{
        background: colors.bg,
        minHeight: "100vh",
        fontFamily: fonts.sans,
        display: "flex",
        justifyContent: "center",
        boxSizing: "border-box",
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 460,
          padding: "120px 32px 32px",
          display: "flex",
          flexDirection: "column",
          boxSizing: "border-box",
        }}
      >
        <div
          style={{
            fontFamily: fonts.emo,
            fontSize: 28,
            fontWeight: 700,
            color: colors.ink,
            lineHeight: 1.3,
            letterSpacing: -1,
          }}
        >
          초대 링크가 만료됐어요
        </div>
        <div
          style={{
            marginTop: 12,
            fontSize: 14,
            color: colors.inkSoft,
            lineHeight: 1.6,
          }}
        >
          우리가 갈 지도 앱에서 새 초대를 받을 수 있어요.
        </div>

        <div style={{ flex: 1 }} />

        <div style={{ display: "flex", justifyContent: "center", paddingTop: 24 }}>
          <a href={IOS_APP_URL}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src="/app-store-badge.svg"
              alt="App Store에서 다운로드"
              width={160}
              style={{ display: "block" }}
            />
          </a>
        </div>
      </div>
    </div>
  );
}
