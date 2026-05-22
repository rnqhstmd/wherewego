"use client";

import { useEffect, useRef, useState } from "react";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * 클러스터 안내 배너 (설계 §9 FR-MAP-5, M-7).
 *
 * 클러스터가 화면에 존재하고 (`visible=true`) localStorage에 표시 기록이
 * 없으면 top:60px 위치에 흰 카드로 한 번만 노출. 3초 후 자동 닫힘 +
 * localStorage 기록.
 *
 * - 디자인 토큰의 `colors.cta`(rust)는 안내용 24px 원에 인라인 적용.
 *   공용 `Cluster.tsx`(32px, 마커용)와 의도적으로 분리.
 * - SSR 안전: localStorage 접근은 effect 내부 + ref로 dismissed 추적.
 *   (외부 시스템=localStorage/timer 어댑터 effect 패턴; cascading render 회피)
 */

const STORAGE_KEY = "map.clusterBannerShown";
const AUTO_DISMISS_MS = 2000;

interface ClusterBannerProps {
  /** 현재 viewport에 클러스터가 1개 이상 존재하는지. */
  visible: boolean;
}

export default function ClusterBanner({ visible }: ClusterBannerProps) {
  // 표시 여부는 렌더 트리거가 필요하므로 state.
  const [shown, setShown] = useState(false);
  // 한 번 노출되거나 localStorage 기록이 있으면 영구 dismiss.
  // render에 영향 없는 외부 시스템 상태이므로 ref.
  const dismissedRef = useRef(false);
  // mount 시 localStorage 1회 조회를 위한 가드.
  const checkedRef = useRef(false);

  useEffect(() => {
    // mount 첫 호출에서만 localStorage 확인.
    if (!checkedRef.current) {
      checkedRef.current = true;
      try {
        if (
          typeof window !== "undefined" &&
          window.localStorage.getItem(STORAGE_KEY) === "1"
        ) {
          dismissedRef.current = true;
        }
      } catch {
        // localStorage 사용 불가 환경: 무시 (이번 세션만 표시).
      }
    }

    if (!visible || dismissedRef.current) return;

    // 외부 시스템(timer + localStorage) 어댑터: 표시 → 3초 후 영구 기록.
    // 표시 전이도 micro-task로 분리하여 cascading render를 끊는다
    // (MapClient의 geo 어댑터 effect와 동일 패턴).
    const openTimer = window.setTimeout(() => {
      setShown(true);
    }, 0);
    const closeTimer = window.setTimeout(() => {
      dismissedRef.current = true;
      try {
        window.localStorage.setItem(STORAGE_KEY, "1");
      } catch {
        // 무시.
      }
      setShown(false);
    }, AUTO_DISMISS_MS);
    return () => {
      window.clearTimeout(openTimer);
      window.clearTimeout(closeTimer);
    };
  }, [visible]);

  if (!shown) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        position: "absolute",
        top: 60,
        left: "50%",
        transform: "translateX(-50%)",
        whiteSpace: "nowrap",
        background: colors.panel,
        padding: "8px 16px",
        borderRadius: 999,
        border: `1px solid ${colors.hairline}`,
        boxShadow: `0 2px 10px ${colors.shadow}`,
        fontFamily: fonts.sans,
        fontSize: 12,
        color: colors.ink,
        zIndex: 25,
      }}
    >
      가까운 핀이 묶여있어요.{" "}
      <span style={{ color: colors.cta, fontWeight: 600 }}>
        탭하거나 확대하면
      </span>{" "}
      펼쳐져요
    </div>
  );
}
