"use client";

import { useEffect, useMemo, useState } from "react";

import { BtnSub } from "@/components/ui/BtnSub";
import { IOS_APP_URL } from "@/lib/config/appStore";
import { colors, fonts } from "@/lib/design/tokens";

import type { InviteLinkPreviewResponse } from "@/lib/api/group-client";

interface InvitePreviewClientProps {
  slug: string;
  preview: InviteLinkPreviewResponse;
}

/**
 * 단축 슬러그 진입 후 보이는 초대 랜딩.
 *
 * 웹 가입은 종료(앱 전용)되어 "합류하기" 동선이 없다. 대신:
 * - 초대 코드(slug)를 크게 표시 + "코드 복사" 버튼.
 * - "wherewego 앱을 설치하고 이 코드를 입력하세요" 안내.
 * - App Store 배지(NEXT_PUBLIC_IOS_APP_URL). 미설정 시에도 배지 노출.
 */
export function InvitePreviewClient({ slug, preview }: InvitePreviewClientProps) {
  const [copied, setCopied] = useState(false);
  const [now, setNow] = useState<number>(() => Date.now());

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const remainingText = useMemo(() => {
    const diff = new Date(preview.expiresAt).getTime() - now;
    if (Number.isNaN(diff) || diff <= 0) return "곧 만료돼요";
    const totalMin = Math.floor(diff / 60000);
    const days = Math.floor(totalMin / (60 * 24));
    const hours = Math.floor((totalMin % (60 * 24)) / 60);
    const minutes = totalMin % 60;
    if (days > 0) return `${days}일 ${hours}시간 남음`;
    if (hours > 0) return `${hours}시간 ${minutes}분 남음`;
    return `${minutes}분 남음`;
  }, [preview.expiresAt, now]);

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(slug);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // FR-8: navigator.clipboard 미지원(비-HTTPS/구형 브라우저) — 직접 복사 안내.
      window.prompt("아래 코드를 길게 눌러 복사하세요", slug);
    }
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
          padding: "80px 32px 32px",
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
            whiteSpace: "pre-wrap",
          }}
        >
          {"함께 갈 곳을\n모아두는 공간이에요"}
        </div>

        <div
          style={{
            marginTop: 12,
            fontSize: 14,
            color: colors.inkSoft,
            lineHeight: 1.6,
          }}
        >
          초대 코드로 wherewego에 합류하세요.
        </div>

        <div
          style={{
            marginTop: 32,
            background: colors.panel,
            borderRadius: 14,
            border: `1px solid ${colors.hairline}`,
            padding: "18px 22px",
            display: "flex",
            flexDirection: "column",
            gap: 6,
          }}
          aria-live="polite"
        >
          <div
            style={{
              fontFamily: fonts.emo,
              fontSize: 20,
              fontWeight: 700,
              color: colors.ink,
              wordBreak: "break-all",
            }}
          >
            {preview.groupName}
          </div>
          <div
            style={{
              marginTop: 4,
              fontFamily: fonts.mono,
              fontSize: 12,
              color: colors.inkSoft,
            }}
          >
            {remainingText}
          </div>
        </div>

        <div
          style={{
            marginTop: 14,
            background: colors.panel,
            borderRadius: 14,
            border: `1px solid ${colors.hairline}`,
            padding: "18px 22px",
            display: "flex",
            flexDirection: "column",
            gap: 12,
          }}
        >
          <div
            style={{
              fontSize: 12,
              color: colors.inkFaint,
              letterSpacing: 0.4,
            }}
          >
            초대 코드
          </div>
          <div
            style={{
              fontFamily: fonts.mono,
              fontSize: 28,
              fontWeight: 700,
              color: colors.ink,
              letterSpacing: 4,
              wordBreak: "break-all",
            }}
          >
            {slug}
          </div>
          <BtnSub
            onClick={onCopy}
            style={{ width: "100%", padding: "12px 0", fontSize: 14 }}
          >
            {copied ? "복사됐어요" : "코드 복사"}
          </BtnSub>
        </div>

        <div
          style={{
            marginTop: 16,
            fontSize: 14,
            color: colors.inkSoft,
            lineHeight: 1.6,
          }}
        >
          wherewego 앱을 설치하고 이 코드를 입력하세요.
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
