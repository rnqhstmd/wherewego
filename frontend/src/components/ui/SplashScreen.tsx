"use client";

import type { CSSProperties } from "react";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { colors, fonts } from "@/lib/design/tokens";
import { GlobeBg } from "./GlobeBg";

interface SplashScreenProps {
  redirectTo: string;
  delay?: number;
  className?: string;
  style?: CSSProperties;
}

/**
 * 스플래시 화면 — screens-basic.jsx::SplashScreen 1:1 변환.
 * 마운트 시 delay(ms) 후 redirectTo 로 router.replace.
 */
export function SplashScreen({
  redirectTo,
  delay = 1500,
  className,
  style,
}: SplashScreenProps) {
  const router = useRouter();

  useEffect(() => {
    const t = setTimeout(() => {
      router.replace(redirectTo);
    }, delay);
    return () => clearTimeout(t);
  }, [router, redirectTo, delay]);

  return (
    <div
      className={className}
      style={{
        width: "100%",
        height: "100vh",
        background: colors.bg,
        fontFamily: fonts.sans,
        position: "relative",
        overflow: "hidden",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        ...style,
      }}
    >
      <div
        style={{
          position: "absolute",
          inset: 0,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <GlobeBg w={520} h={520} style={{ opacity: 0.4 }} />
      </div>
      <div
        style={{
          position: "relative",
          zIndex: 2,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 18,
        }}
      >
        <div
          style={{
            fontFamily: fonts.emo,
            fontSize: 48,
            fontWeight: 700,
            color: colors.ink,
            letterSpacing: -1.5,
            lineHeight: 1.1,
            textAlign: "center",
          }}
        >
          우리가 갈 지도
        </div>
        {/* Loading dots */}
        <div style={{ display: "flex", gap: 6, marginTop: 12 }}>
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              style={{
                width: 6,
                height: 6,
                borderRadius: "50%",
                background: colors.cta,
                opacity: i === 1 ? 1 : 0.4,
              }}
            />
          ))}
        </div>
      </div>
    </div>
  );
}
