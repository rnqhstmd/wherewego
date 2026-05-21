"use client";

import { useEffect, useState } from "react";

export interface KeyboardInsets {
  /** 키보드가 차지하는 픽셀 높이. 키보드가 닫혀 있으면 0. */
  keyboardHeight: number;
  /** {@link #keyboardHeight} 가 {@link #KEYBOARD_THRESHOLD_PX} 초과인 경우 true. */
  keyboardOpen: boolean;
}

/**
 * 키보드 가시화 판정 임계값.
 *
 * <p>한국어 IME 후보창은 일반적으로 40~60px 의 추가 layer 를 그리므로
 * 80px 미만의 변화는 키보드 미등장으로 간주한다. 모바일 Safari/Chrome 키보드는
 * 200px 이상이므로 이 임계값은 keyboard 와 IME 노이즈를 안정적으로 분리한다.</p>
 */
const KEYBOARD_THRESHOLD_PX = 80;

const INITIAL: KeyboardInsets = { keyboardHeight: 0, keyboardOpen: false };

/**
 * 모바일 키보드의 노출 여부와 높이를 visualViewport API 기반으로 추적한다.
 *
 * <p>iOS Safari 13+, Android Chrome 61+ 에서 동작한다. 미지원 환경에서는
 * 영구적으로 {@link #INITIAL} 을 반환한다.</p>
 *
 * <p>이벤트 폭주를 막기 위해 {@code requestAnimationFrame} 으로 디바운스하며,
 * {@code visualViewport.offsetTop} 이 핀치 줌 + 스크롤 조합에서 음수가 될 수 있어
 * {@code Math.max(0, offsetTop)} 으로 가드한다.</p>
 *
 * @see https://developer.mozilla.org/en-US/docs/Web/API/VisualViewport
 */
export function useKeyboardInsets(): KeyboardInsets {
  const [insets, setInsets] = useState<KeyboardInsets>(INITIAL);

  useEffect(() => {
    if (typeof window === "undefined" || !window.visualViewport) return;
    const vv = window.visualViewport;
    let rafId = 0;

    const compute = (): KeyboardInsets => {
      const delta =
        window.innerHeight - vv.height - Math.max(0, vv.offsetTop);
      const kb = Math.max(0, delta);
      return {
        keyboardHeight: kb,
        keyboardOpen: kb > KEYBOARD_THRESHOLD_PX,
      };
    };

    const update = () => {
      cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(() => {
        const next = compute();
        setInsets((prev) =>
          prev.keyboardHeight === next.keyboardHeight &&
          prev.keyboardOpen === next.keyboardOpen
            ? prev
            : next,
        );
      });
    };

    vv.addEventListener("resize", update);
    vv.addEventListener("scroll", update);
    // 초기 동기화 — visualViewport 가 mount 시점에 이미 변형되어 있을 수 있다.
    update();

    return () => {
      cancelAnimationFrame(rafId);
      vv.removeEventListener("resize", update);
      vv.removeEventListener("scroll", update);
    };
  }, []);

  return insets;
}
