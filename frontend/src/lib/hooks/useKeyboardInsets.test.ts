import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useKeyboardInsets } from "./useKeyboardInsets";

interface FakeVisualViewport {
  height: number;
  offsetTop: number;
  addEventListener: (type: string, listener: () => void) => void;
  removeEventListener: (type: string, listener: () => void) => void;
}

function installFakeVisualViewport(opts: {
  innerHeight: number;
  vvHeight: number;
  offsetTop?: number;
}) {
  const listeners: Record<string, Set<() => void>> = {};
  const vv: FakeVisualViewport = {
    height: opts.vvHeight,
    offsetTop: opts.offsetTop ?? 0,
    addEventListener: (type, listener) => {
      if (!listeners[type]) listeners[type] = new Set();
      listeners[type]!.add(listener);
    },
    removeEventListener: (type, listener) => {
      listeners[type]?.delete(listener);
    },
  };
  Object.defineProperty(window, "visualViewport", {
    configurable: true,
    value: vv,
  });
  Object.defineProperty(window, "innerHeight", {
    configurable: true,
    value: opts.innerHeight,
  });
  return {
    vv,
    fire: (type: "resize" | "scroll") => {
      listeners[type]?.forEach((l) => l());
    },
    setHeight: (h: number) => {
      vv.height = h;
    },
    setOffsetTop: (t: number) => {
      vv.offsetTop = t;
    },
  };
}

function uninstallVisualViewport() {
  Object.defineProperty(window, "visualViewport", {
    configurable: true,
    value: undefined,
  });
}

describe("useKeyboardInsets", () => {
  beforeEach(() => {
    // rAF 동기 실행 — 테스트 결정성 확보
    vi.stubGlobal(
      "requestAnimationFrame",
      (cb: FrameRequestCallback) => {
        cb(0);
        return 0;
      },
    );
    vi.stubGlobal("cancelAnimationFrame", () => {});
    vi.useFakeTimers({ shouldAdvanceTime: false });
  });
  afterEach(() => {
    uninstallVisualViewport();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  // ============================== 회귀 보존 ==============================

  it("visualViewport 미지원 환경 → 영구 {0, false}", () => {
    uninstallVisualViewport();
    const { result } = renderHook(() => useKeyboardInsets());
    expect(result.current).toEqual({
      keyboardHeight: 0,
      keyboardOpen: false,
    });
  });

  it("키보드 닫혀 있음 (delta 0) → {0, false}", () => {
    installFakeVisualViewport({ innerHeight: 800, vvHeight: 800 });
    const { result } = renderHook(() => useKeyboardInsets());
    expect(result.current).toEqual({
      keyboardHeight: 0,
      keyboardOpen: false,
    });
  });

  it("키보드 등장 (delta 500 > open 임계 120) → leading commit으로 즉시 {500, true}", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    act(() => {
      ctx.setHeight(300);
      ctx.fire("resize");
    });
    // open 전환은 leading이라 throttle 안 타고 즉시 보임
    expect(result.current).toEqual({
      keyboardHeight: 500,
      keyboardOpen: true,
    });
  });

  it("IME 후보창 노이즈 (delta 60 < open 임계 120) → throttle 후 {60, false}", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    act(() => {
      ctx.setHeight(740);
      ctx.fire("resize");
      vi.advanceTimersByTime(60);
    });
    expect(result.current).toEqual({
      keyboardHeight: 60,
      keyboardOpen: false,
    });
  });

  it("offsetTop 음수 → Math.max(0) 가드로 보정", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
      offsetTop: -50,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    act(() => {
      ctx.fire("resize");
      vi.advanceTimersByTime(60);
    });
    expect(result.current).toEqual({
      keyboardHeight: 0,
      keyboardOpen: false,
    });
  });

  // ============================== Hysteresis ==============================

  it("hysteresis: prev=false + delta 100 (open 임계 120 미달) → false 유지", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    act(() => {
      ctx.setHeight(700);
      ctx.fire("resize");
      vi.advanceTimersByTime(60);
    });
    expect(result.current).toEqual({
      keyboardHeight: 100,
      keyboardOpen: false,
    });
  });

  it("hysteresis: prev=false + delta 130 (open 임계 초과) → true 전환 (leading)", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    act(() => {
      ctx.setHeight(670);
      ctx.fire("resize");
    });
    // open 전환은 즉시 — throttle 진행 없이도 보임
    expect(result.current).toEqual({
      keyboardHeight: 130,
      keyboardOpen: true,
    });
  });

  it("hysteresis: prev=true + delta 80 (close 임계 60 초과) → true 유지", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    // 1단계 — open
    act(() => {
      ctx.setHeight(500);
      ctx.fire("resize");
    });
    expect(result.current.keyboardOpen).toBe(true);
    // 2단계 — delta 80 (close 임계 60 초과)
    act(() => {
      ctx.setHeight(720);
      ctx.fire("resize");
      vi.advanceTimersByTime(60);
    });
    expect(result.current).toEqual({
      keyboardHeight: 80,
      keyboardOpen: true,
    });
  });

  it("hysteresis: prev=true + delta 50 (close 임계 미달) → false 전환 (throttle 후)", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    // 1단계 — open
    act(() => {
      ctx.setHeight(500);
      ctx.fire("resize");
    });
    expect(result.current.keyboardOpen).toBe(true);
    // 2단계 — delta 50 (close 임계 60 미달)
    act(() => {
      ctx.setHeight(750);
      ctx.fire("resize");
      vi.advanceTimersByTime(60);
    });
    expect(result.current).toEqual({
      keyboardHeight: 50,
      keyboardOpen: false,
    });
  });

  // ============================== 비대칭 throttle ==============================

  it("throttle: open burst (delta 0→300 3회) → 첫 이벤트에서 leading commit", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    act(() => {
      ctx.setHeight(500);
      ctx.fire("resize");
      // leading commit 발생 — throttle 진행 없이도 즉시 보임
    });
    expect(result.current).toEqual({
      keyboardHeight: 300,
      keyboardOpen: true,
    });
    // 추가 변화는 throttle을 타지만 같은 open 상태라 keyboardHeight만 업데이트
    act(() => {
      ctx.setHeight(490);
      ctx.fire("resize");
      ctx.setHeight(480);
      ctx.fire("resize");
      vi.advanceTimersByTime(60);
    });
    expect(result.current).toEqual({
      keyboardHeight: 320,
      keyboardOpen: true,
    });
  });

  it("throttle: close burst → 마지막 값만 trailing commit", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    // open — innerHeight=800, vvHeight=500 → delta=300, open commit
    act(() => {
      ctx.setHeight(500);
      ctx.fire("resize");
    });
    expect(result.current).toEqual({
      keyboardHeight: 300,
      keyboardOpen: true,
    });
    // close 도중 burst (3회 resize) — 60ms 안에 끝나면 마지막만 commit
    act(() => {
      ctx.setHeight(700); // delta=100
      ctx.fire("resize");
      vi.advanceTimersByTime(20);
      ctx.setHeight(750); // delta=50
      ctx.fire("resize");
      vi.advanceTimersByTime(20);
      ctx.setHeight(800); // delta=0
      ctx.fire("resize");
      // 마지막 resize 후 59ms — throttle(60ms) 미달, commit 없음
      vi.advanceTimersByTime(59);
    });
    // 직전 open commit 값(300) 그대로 — 아직 trailing commit 안 됨
    expect(result.current).toEqual({
      keyboardHeight: 300,
      keyboardOpen: true,
    });
    // 60ms 경과 → 마지막 setHeight(800)의 computeNow() = delta 0 commit
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current).toEqual({
      keyboardHeight: 0,
      keyboardOpen: false,
    });
  });
});
