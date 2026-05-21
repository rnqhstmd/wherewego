import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

import { createNotificationSseClient } from "./sseClient";
import type { ConnectionState } from "./types";

// 테스트용 가짜 EventSource — onopen/onerror를 외부에서 트리거할 수 있게 노출
interface MockEventSourceInstance {
  url: string;
  onopen: ((this: EventSource, ev: Event) => unknown) | null;
  onerror: ((this: EventSource, ev: Event) => unknown) | null;
  addEventListener: ReturnType<typeof vi.fn>;
  close: ReturnType<typeof vi.fn>;
  triggerError: () => void;
}

let instances: MockEventSourceInstance[] = [];

class MockEventSource {
  url: string;
  onopen: ((this: EventSource, ev: Event) => unknown) | null = null;
  onerror: ((this: EventSource, ev: Event) => unknown) | null = null;
  addEventListener = vi.fn();
  close = vi.fn();

  constructor(url: string, _init?: EventSourceInit) {
    this.url = url;
    const self = this;
    const instance: MockEventSourceInstance = {
      url,
      get onopen() {
        return self.onopen;
      },
      set onopen(v) {
        self.onopen = v;
      },
      get onerror() {
        return self.onerror;
      },
      set onerror(v) {
        self.onerror = v;
      },
      addEventListener: this.addEventListener,
      close: this.close,
      triggerError: () => {
        self.onerror?.call(self as unknown as EventSource, new Event("error"));
      },
    };
    instances.push(instance);
  }
}

describe("createNotificationSseClient", () => {
  beforeEach(() => {
    instances = [];
    vi.useFakeTimers();
    vi.stubGlobal("EventSource", MockEventSource);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("지수 백오프: 첫 실패 후 2s 재시도, 두 번째 실패 후 4s 재시도", () => {
    const onStateChange = vi.fn<(state: ConnectionState) => void>();
    const client = createNotificationSseClient({
      url: "/api/v1/notifications/stream",
      onStateChange,
      onNotification: vi.fn(),
    });

    client.start();
    expect(instances).toHaveLength(1);

    // 첫 번째 onerror → 2s 후 재시도 예약
    instances[0]!.triggerError();
    expect(instances).toHaveLength(1);

    // 2s 미만에는 재시도 없음
    vi.advanceTimersByTime(1_999);
    expect(instances).toHaveLength(1);

    // 2s 도달 → 두 번째 EventSource 생성
    vi.advanceTimersByTime(1);
    expect(instances).toHaveLength(2);

    // 두 번째도 onerror → 4s 후 재시도 예약
    instances[1]!.triggerError();
    vi.advanceTimersByTime(3_999);
    expect(instances).toHaveLength(2);

    vi.advanceTimersByTime(1);
    expect(instances).toHaveLength(3);

    client.stop();
  });

  it("(FR-8) 5회 실패 후 더 이상 재시도하지 않고 failed 상태 발사", () => {
    const onStateChange = vi.fn<(state: ConnectionState) => void>();
    const client = createNotificationSseClient({
      url: "/api/v1/notifications/stream",
      onStateChange,
      onNotification: vi.fn(),
    });

    client.start();

    // 5번 재시도: 각 단계에서 onerror 발사 후 백오프 만료시켜 재연결 시킴
    // delays: 2s, 4s, 8s, 16s, 30s (32s가 cap에 의해 30s)
    const delays = [2_000, 4_000, 8_000, 16_000, 30_000];
    for (let i = 0; i < delays.length; i++) {
      expect(instances).toHaveLength(i + 1);
      instances[i]!.triggerError();
      vi.advanceTimersByTime(delays[i]!);
    }
    // 5번의 재시도까지 모두 발생 → 총 6개 EventSource(초기 1 + 재시도 5)
    expect(instances).toHaveLength(6);

    // 6번째도 실패 → 6번째 재시도는 하지 않고 failed 상태 발사
    onStateChange.mockClear();
    instances[5]!.triggerError();

    // failed 상태가 발사됨
    expect(onStateChange).toHaveBeenCalledWith("failed");

    // 추가 시간 경과해도 새 EventSource 생성 없음
    vi.advanceTimersByTime(60_000);
    expect(instances).toHaveLength(6);

    client.stop();
  });
});
