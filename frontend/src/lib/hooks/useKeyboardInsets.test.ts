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
  });
  afterEach(() => {
    uninstallVisualViewport();
    vi.unstubAllGlobals();
  });

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

  it("키보드 등장 (delta 500) → {500, true}", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    act(() => {
      ctx.setHeight(300);
      ctx.fire("resize");
    });
    expect(result.current).toEqual({
      keyboardHeight: 500,
      keyboardOpen: true,
    });
  });

  it("IME 후보창 노이즈 (delta 60 < threshold 80) → {60, false}", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    act(() => {
      ctx.setHeight(740);
      ctx.fire("resize");
    });
    expect(result.current).toEqual({
      keyboardHeight: 60,
      keyboardOpen: false,
    });
  });

  it("threshold 경계값 (delta 80) → keyboardOpen=false (strict >)", () => {
    const ctx = installFakeVisualViewport({
      innerHeight: 800,
      vvHeight: 800,
    });
    const { result } = renderHook(() => useKeyboardInsets());
    act(() => {
      ctx.setHeight(720);
      ctx.fire("resize");
    });
    expect(result.current).toEqual({
      keyboardHeight: 80,
      keyboardOpen: false,
    });
    act(() => {
      ctx.setHeight(719);
      ctx.fire("resize");
    });
    expect(result.current).toEqual({
      keyboardHeight: 81,
      keyboardOpen: true,
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
    });
    // delta = 800 - 800 - max(0, -50) = 0
    expect(result.current).toEqual({
      keyboardHeight: 0,
      keyboardOpen: false,
    });
  });
});
