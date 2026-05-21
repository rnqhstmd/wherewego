import type { NotificationStreamEvent, ConnectionState } from "./types";

export interface SseClientOptions {
  url: string;
  onConnected?: () => void;
  onNotification: (payload: NotificationStreamEvent) => void;
  onStateChange?: (state: ConnectionState) => void;
}

export interface SseClient {
  start(): void;
  stop(): void;
}

const INITIAL_BACKOFF_MS = 2_000;
const MAX_BACKOFF_MS = 30_000;
const MAX_RETRIES = 5;

/**
 * EventSource 기반 알림 SSE 클라이언트.
 *
 * <p>재연결 정책 (FR-8):</p>
 * <ul>
 *   <li>초기 backoff 2초, 매 실패마다 2배 (2 → 4 → 8 → 16 → 30 cap)</li>
 *   <li>최대 5회 재시도 — 5회 실패 시 {@code failed} 상태로 영구 중단</li>
 *   <li>정상 연결(onopen 또는 connected event) 시 카운터 reset</li>
 *   <li>{@code stop()} 호출 시 {@code closed} 상태 + 더 이상 재연결 안 함</li>
 * </ul>
 *
 * <p>본 모듈은 {@code EventSource} 전역에만 의존한다. SSR 환경에서 호출되지
 * 않도록 호출자가 {@code typeof window !== "undefined"} 가드를 두어야 한다.</p>
 *
 * <p>모든 에러는 silent — 호출자는 {@code onStateChange} 로 상태 변화를
 * 관찰해 UX 처리한다.</p>
 */
export function createNotificationSseClient(options: SseClientOptions): SseClient {
  let eventSource: EventSource | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let retryCount = 0;
  let stopped = false;
  let preflightDone = false;

  function setState(state: ConnectionState): void {
    options.onStateChange?.(state);
  }

  function cleanupEventSource(): void {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
  }

  function scheduleReconnect(): void {
    if (stopped) return;
    if (retryCount >= MAX_RETRIES) {
      setState("failed");
      return;
    }
    const delay = Math.min(
      INITIAL_BACKOFF_MS * Math.pow(2, retryCount),
      MAX_BACKOFF_MS,
    );
    retryCount += 1;
    setState("connecting");
    retryTimer = setTimeout(() => {
      void connect();
    }, delay);
  }

  /**
   * EventSource는 응답 status code를 직접 노출하지 않으므로,
   * 첫 연결 전에 fetch로 인증 상태를 미리 확인한다.
   * 401/403이면 5회 재시도 없이 즉시 failed 상태로 수렴한다.
   */
  async function preflightAuth(): Promise<"ok" | "unauthorized" | "error"> {
    try {
      const controller = new AbortController();
      const res = await fetch(options.url, {
        method: "GET",
        headers: { Accept: "text/event-stream" },
        credentials: "include",
        signal: controller.signal,
      });
      // status를 확인했으므로 즉시 종료 (SSE 연결을 점유하지 않도록)
      controller.abort();
      if (res.status === 401 || res.status === 403) return "unauthorized";
      if (!res.ok) return "error";
      return "ok";
    } catch (e) {
      // abort에 의한 AbortError는 정상 (이미 status를 받았으므로)
      if (e instanceof Error && e.name === "AbortError") return "ok";
      return "error";
    }
  }

  async function connect(): Promise<void> {
    if (stopped) return;
    retryTimer = null;
    cleanupEventSource();
    setState("connecting");

    // 첫 진입 시에만 preflight (이후 재연결은 EventSource onerror 흐름)
    if (!preflightDone) {
      const auth = await preflightAuth();
      if (stopped) return;
      preflightDone = true;
      if (auth === "unauthorized") {
        setState("failed");
        return;
      }
      // "error"인 경우는 일시적 네트워크 이슈일 수 있으므로 EventSource 시도 진행
    }

    try {
      eventSource = new EventSource(options.url, { withCredentials: true });
    } catch {
      scheduleReconnect();
      return;
    }

    eventSource.onopen = () => {
      retryCount = 0;
      setState("open");
    };

    eventSource.addEventListener("connected", () => {
      retryCount = 0;
      setState("open");
      options.onConnected?.();
    });

    eventSource.addEventListener("notification", (ev: MessageEvent) => {
      try {
        const payload = JSON.parse(ev.data) as NotificationStreamEvent;
        options.onNotification(payload);
      } catch {
        // payload 파싱 실패는 무시 (다음 이벤트로 진행)
      }
    });

    eventSource.onerror = () => {
      cleanupEventSource();
      if (stopped) {
        setState("closed");
        return;
      }
      scheduleReconnect();
    };
  }

  return {
    start(): void {
      stopped = false;
      retryCount = 0;
      preflightDone = false;
      if (retryTimer) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      void connect();
    },
    stop(): void {
      stopped = true;
      if (retryTimer) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      cleanupEventSource();
      setState("closed");
    },
  };
}
