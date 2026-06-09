"use client";

import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { IOS_APP_URL, isAppStoreReady } from "@/lib/config/appStore";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * 만료/소진/존재하지 않는 슬러그에 대한 안내 상태.
 * 정보 노출 방지를 위해 백엔드도 404 INVITE_LINK_NOT_FOUND 로 통일하므로
 * 사용자에게는 동일하게 "만료" 메시지를 보여준다.
 *
 * 웹 가입은 종료(앱 전용)되어 "홈으로"(/map) 동선이 없다.
 * 대신 앱 설치를 유도한다(미설정 시 버튼 비활성 + "출시 예정").
 */
export function InviteExpiredState() {
  const onOpenAppStore = () => {
    if (IOS_APP_URL) window.location.href = IOS_APP_URL;
  };

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
            whiteSpace: "pre-wrap",
          }}
        >
          짝꿍에게 새 초대 링크를 받아주세요.
          {"\n"}wherewego 앱에서 새 초대를 받을 수 있어요.
        </div>

        <div style={{ flex: 1 }} />

        <BtnPrimary
          onClick={onOpenAppStore}
          disabled={!isAppStoreReady}
          style={{ width: "100%", padding: "14px 0", fontSize: 15 }}
        >
          {isAppStoreReady ? "App Store에서 받기" : "출시 예정"}
        </BtnPrimary>
      </div>
    </div>
  );
}
