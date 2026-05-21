"use client";

import { useEffect, useRef, useState } from "react";

export interface KeyboardInsets {
  /** 키보드가 차지하는 픽셀 높이. 키보드가 닫혀 있으면 0. */
  keyboardHeight: number;
  /**
   * 키보드 노출 여부. open 임계와 close 임계가 분리되어(hysteresis) 한국어 IME 후보창과
   * 핀치 줌/스크롤 noise를 안정적으로 분리한다.
   */
  keyboardOpen: boolean;
}

/**
 * 키보드 등장 진입 임계. delta가 이 값을 초과해야 keyboardOpen=true 전환.
 * iOS Safari 키보드는 200px 이상, 한국어 IME 후보창은 40~90px 이라 120px 이면 안전 margin.
 */
const OPEN_THRESHOLD_PX = 120;

/**
 * 키보드 닫힘 판정 임계. 이미 open 상태에서 delta가 이 값 이하면 false 전환.
 * IME 후보창 단독 노출(40~60px)과 키보드 잔여 시각 효과를 구분하기 위한 보수적 닫힘 기준.
 */
const CLOSE_THRESHOLD_PX = 60;

/**
 * close 또는 미세 변화 commit을 60ms 모은다. burst 시 마지막 값만 commit하여 들썩임을 흡수.
 * open 전환은 leading commit이라 이 throttle을 타지 않는다.
 */
const TAIL_THROTTLE_MS = 60;

const INITIAL: KeyboardInsets = { keyboardHeight: 0, keyboardOpen: false };

/**
 * 모바일 키보드의 노출 여부와 높이를 visualViewport API 기반으로 추적한다.
 *
 * <p>iOS Safari 13+, Android Chrome 61+ 에서 동작한다. 미지원 환경에서는
 * 영구적으로 {@link #INITIAL} 을 반환한다.</p>
 *
 * <p>주요 정책:
 * <ul>
 *   <li><b>Hysteresis</b>: open 임계 {@link #OPEN_THRESHOLD_PX}, close 임계 {@link #CLOSE_THRESHOLD_PX}.
 *       80px 영역에서 IME 후보창을 키보드로 오인하지 않는다.</li>
 *   <li><b>비대칭 throttle</b>: open 전환은 leading commit(즉시), close/미세 변화는 trailing
 *       {@link #TAIL_THROTTLE_MS} ms throttle. 키보드 등장 latency는 최소화하면서 닫힘 noise는 흡수.</li>
 *   <li><b>prevOpenRef</b>: 클로저 캡처 회피를 위해 직전 commit된 open 상태를 ref로 보관.</li>
 *   <li><b>offsetTop 가드</b>: pinch zoom + scroll 조합에서 음수가 될 수 있어 {@code Math.max(0, offsetTop)}.</li>
 * </ul></p>
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/VisualViewport
 */
export function useKeyboardInsets(): KeyboardInsets {
  const [insets, setInsets] = useState<KeyboardInsets>(INITIAL);
  const prevOpenRef = useRef(false);

  useEffect(() => {
    if (typeof window === "undefined" || !window.visualViewport) return;
    const vv = window.visualViewport;
    let rafId = 0;
    let tailTimer: ReturnType<typeof setTimeout> | null = null;

    const computeNow = (): KeyboardInsets => {
      const delta =
        window.innerHeight - vv.height - Math.max(0, vv.offsetTop);
      const kb = Math.max(0, delta);
      const wasOpen = prevOpenRef.current;
      const open = wasOpen
        ? kb > CLOSE_THRESHOLD_PX
        : kb > OPEN_THRESHOLD_PX;
      return { keyboardHeight: kb, keyboardOpen: open };
    };

    const commit = (next: KeyboardInsets) => {
      setInsets((prev) => {
        if (
          prev.keyboardHeight === next.keyboardHeight &&
          prev.keyboardOpen === next.keyboardOpen
        ) {
          return prev;
        }
        prevOpenRef.current = next.keyboardOpen;
        return next;
      });
    };

    const update = () => {
      cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(() => {
        const next = computeNow();
        const wasOpen = prevOpenRef.current;
        // 비대칭: open 전환은 leading commit (latency 최소화)
        if (!wasOpen && next.keyboardOpen) {
          if (tailTimer) {
            clearTimeout(tailTimer);
            tailTimer = null;
          }
          commit(next);
          return;
        }
        // 그 외: trailing throttle (burst 흡수). commit 시점에 hysteresis 재계산하여
        // 60ms 사이 prev가 변해도 정확한 임계 판정을 유지한다.
        if (tailTimer) clearTimeout(tailTimer);
        tailTimer = setTimeout(() => {
          tailTimer = null;
          commit(computeNow());
        }, TAIL_THROTTLE_MS);
      });
    };

    vv.addEventListener("resize", update);
    vv.addEventListener("scroll", update);
    update();

    return () => {
      cancelAnimationFrame(rafId);
      if (tailTimer) clearTimeout(tailTimer);
      vv.removeEventListener("resize", update);
      vv.removeEventListener("scroll", update);
    };
  }, []);

  return insets;
}
