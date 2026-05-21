import {
  describe,
  it,
  expect,
  beforeEach,
  afterEach,
  vi,
  type MockInstance,
} from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useGroupPinSync } from "./useGroupPinSync";
import * as pinClient from "@/lib/api/pin-client";
import { ApiError } from "@/lib/api/http-client";
import type { PinListResponse, PinSummaryResponse } from "@/lib/api/types";

const GROUP_ID = 42;

function pin(id: number): PinSummaryResponse {
  return {
    id,
    groupId: GROUP_ID,
    createdBy: 1,
    createdByNickname: null,
    placeName: `place-${id}`,
    address: null,
    latitude: 37.5,
    longitude: 127.0,
    instagramUrl: null,
    memo: null,
    memoSource: null,
    tag: "PLACE",
    createdAt: "2026-05-21T00:00:00Z",
  };
}

function setVisibility(state: "visible" | "hidden") {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: () => state,
  });
  document.dispatchEvent(new Event("visibilitychange"));
}

describe("useGroupPinSync", () => {
  let listSpy: MockInstance<
    (groupId: number, signal?: AbortSignal) => Promise<PinListResponse>
  >;
  let warnSpy: MockInstance<(...args: unknown[]) => void>;

  beforeEach(() => {
    vi.useFakeTimers();
    setVisibility("visible");
    listSpy = vi
      .spyOn(pinClient, "listPinsClient")
      .mockResolvedValue({ items: [pin(1)] });
    warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    listSpy.mockRestore();
    warnSpy.mockRestore();
    setVisibility("visible");
  });

  it("visible 상태에서 mount → 즉시 1회 fetch + onTick 호출", async () => {
    const onTick = vi.fn();
    renderHook(() => useGroupPinSync({ groupId: GROUP_ID, onTick }));

    // 즉시 fetch는 microtask 큐에서 실행됨
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith(GROUP_ID, expect.any(AbortSignal));
    expect(onTick).toHaveBeenCalledWith([pin(1)]);
  });

  it("30s interval 경과마다 추가 fetch", async () => {
    const onTick = vi.fn();
    renderHook(() => useGroupPinSync({ groupId: GROUP_ID, onTick }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(listSpy).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(listSpy).toHaveBeenCalledTimes(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(listSpy).toHaveBeenCalledTimes(3);
  });

  it("hidden 상태에서 mount → fetch 안 함, visible 전환 시 즉시 fetch", async () => {
    setVisibility("hidden");
    const onTick = vi.fn();
    renderHook(() => useGroupPinSync({ groupId: GROUP_ID, onTick }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(listSpy).not.toHaveBeenCalled();

    act(() => setVisibility("visible"));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(listSpy).toHaveBeenCalledTimes(1);
  });

  it("visible → hidden 전환 시 interval 중단 + in-flight abort", async () => {
    const onTick = vi.fn();
    renderHook(() => useGroupPinSync({ groupId: GROUP_ID, onTick }));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(listSpy).toHaveBeenCalledTimes(1);
    const signalFromFirstCall = listSpy.mock.calls[0]?.[1] as AbortSignal;

    act(() => setVisibility("hidden"));
    expect(signalFromFirstCall.aborted).toBe(true);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(listSpy).toHaveBeenCalledTimes(1); // hidden 도중엔 추가 호출 없음
  });

  it("in-flight 중 unmount → controller abort, onTick 미호출", async () => {
    const onTick = vi.fn();
    let resolveFetch: ((res: { items: PinSummaryResponse[] }) => void) | null =
      null;
    listSpy.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveFetch = resolve;
        }),
    );

    const { unmount } = renderHook(() =>
      useGroupPinSync({ groupId: GROUP_ID, onTick }),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(listSpy).toHaveBeenCalledTimes(1);
    const signal = listSpy.mock.calls[0]?.[1] as AbortSignal;

    unmount();
    expect(signal.aborted).toBe(true);

    // 응답이 늦게 도착해도 onTick 호출 안 됨 (cancelled 가드)
    await act(async () => {
      resolveFetch?.({ items: [pin(99)] });
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(onTick).not.toHaveBeenCalled();
  });

  it("401 응답 → polling 영구 중단, 이후 interval 무시", async () => {
    const onTick = vi.fn();
    listSpy.mockRejectedValue(new ApiError("UNAUTHORIZED", "no auth", 401));

    renderHook(() => useGroupPinSync({ groupId: GROUP_ID, onTick }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining("401 Unauthorized"),
    );

    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(listSpy).toHaveBeenCalledTimes(1); // 추가 호출 없음
  });

  it("네트워크 오류 → silent, 다음 interval 정상 호출", async () => {
    const onTick = vi.fn();
    listSpy.mockRejectedValueOnce(new Error("Network error"));
    listSpy.mockResolvedValueOnce({ items: [pin(2)] });

    renderHook(() => useGroupPinSync({ groupId: GROUP_ID, onTick }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(onTick).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(listSpy).toHaveBeenCalledTimes(2);
    expect(onTick).toHaveBeenCalledWith([pin(2)]);
  });
});
