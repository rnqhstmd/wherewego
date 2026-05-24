"use client";

import dynamic from "next/dynamic";
import {
  forwardRef,
  useCallback,
  useEffect,
  useMemo,
  useOptimistic,
  useRef,
  useState,
  useTransition,
} from "react";
import type mapboxgl from "mapbox-gl";
import type {
  PinListResponse,
  PinSummaryResponse,
  PinTag,
  PlaceSearchItem,
} from "@/lib/api/types";
import { apiFetch } from "@/lib/api/http-client";
import { colors, fonts } from "@/lib/design/tokens";
import { Sheet } from "@/components/ui/Sheet";
import { SidePanel } from "@/components/ui/SidePanel";
import { PermissionDialog } from "@/components/ui/PermissionDialog";
import { IconLocation } from "@/components/icons";
import PinPopup from "./_components/PinPopup";
import VisitToast from "./_components/VisitToast";
import VisitMemoSheet from "./_components/VisitMemoSheet";
import ActionBar from "./_components/ActionBar";
import DesktopActionPill from "./_components/DesktopActionPill";
import MobileTopNav from "./_components/MobileTopNav";
import SearchPanelContent from "./_components/SearchPanelContent";
import AddPinPickerContent from "./_components/AddPinPickerContent";
import PinCoordinateEditPicker from "./_components/PinCoordinateEditPicker";
import CrosshairOverlay from "./_components/CrosshairOverlay";
import MemoTagPanelContent from "./_components/MemoTagPanelContent";
import RouletteSpinContent from "./_components/RouletteSpinContent";
import RouletteResultContent from "./_components/RouletteResultContent";
import ClusterBanner from "./_components/ClusterBanner";
import EmptyMapCard from "./_components/EmptyMapCard";
import { TagLegendButton } from "./_components/TagLegendButton";
import MapLoadError, {
  type MapLoadErrorReason,
} from "./_components/MapLoadError";
import { useMediaQuery } from "@/lib/hooks/useMediaQuery";
import { useKeyboardInsets } from "@/lib/hooks/useKeyboardInsets";
import { useGeolocation, type LatLng } from "./_hooks/useGeolocation";
import { useGroupPinSync } from "./_hooks/useGroupPinSync";
import { useVisitDetection } from "./_hooks/useVisitDetection";
import type { MapboxViewHandle } from "./_components/MapboxView";
import {
  pickRandomWithExpansion,
  reRollFromSamePool,
  type RouletteRadiusKm,
} from "./_lib/roulette";
import { PinDeleteConfirm } from "@/app/pins/_components/PinDeleteConfirm";
import {
  deletePinAction,
  updatePinCoordinateAction,
  updatePinMemoAction,
  updatePinPlaceNameAction,
  updatePinTagAction,
} from "./actions";
import type { ActionBarTab, NewPinOrigin } from "./_components/types";
import { useNotifications } from "@/lib/notifications/useNotifications";
import { NotificationBell } from "./_components/notifications/NotificationBell";
import { NotificationToast } from "./_components/notifications/NotificationToast";
import { NotificationPanel } from "./_components/notifications/NotificationPanel";
import type { NotificationPinItem } from "@/lib/notifications/types";

/**
 * MapboxView는 mapbox-gl이 window 의존이므로 ssr:false로 동적 로드.
 * Server Component에서는 ssr:false 옵션이 허용되지 않으므로 반드시 이 Client Component에서 호출.
 *
 * Phase 10: 부모(MapClient)가 ref 로 `triggerVisitCelebration` imperative API 를 호출해야 하지만
 * `next/dynamic` 의 결과 컴포넌트는 ref 전달 동작이 React 19 lazy 변경에 묶여 있어
 * 명시적 forwardRef wrapper 로 감싸 ref 흐름을 보장한다. wrapper 는 forwardRef 자체이므로
 * 내부 dynamic 컴포넌트의 ssr:false / loading fallback 동작을 그대로 유지한다.
 */
const MapboxViewDynamic = dynamic(() => import("./_components/MapboxView"), {
  ssr: false,
  loading: () => (
    <div style={{ position: "absolute", inset: 0, background: colors.bg }} />
  ),
});

type MapboxViewProps = React.ComponentProps<typeof MapboxViewDynamic>;

const MapboxView = forwardRef<MapboxViewHandle, MapboxViewProps>(
  function MapboxView(props, ref) {
    // dynamic 컴포넌트는 React 19 환경에서 ref 를 그대로 전달하지만,
    // 명시적 wrapper 로 ref prop 을 강제 전달하여 미래 dynamic 동작 변화에도 안전하게 한다.
    return <MapboxViewDynamic {...(props as MapboxViewProps)} ref={ref} />;
  },
);

interface MapClientProps {
  initialPins: PinSummaryResponse[];
  groupId: number;
  groupName: string;
  mapboxToken: string;
  mapboxStyleUrl: string | null;
  myNickname: string;
  myId: number;
}

type ActiveSheet =
  | "search"
  | "add"
  | "memo"
  | "roulette"
  | "coordinate-edit"
  | "visit-memo"
  | null;

/**
 * useOptimistic reducer 액션 (Phase 2.8 FR-7).
 *
 * `patch`: 태그/메모 등 필드 부분 머지 (MUST-5).
 * `remove`: 삭제 흐름의 낙관적 제거. 실패 시 transition 종료로 자동 롤백되어
 * 마커가 복원되고 PinPopup 재mount → 인라인 에러 표시 (AC-16).
 */
type OptimisticAction =
  | { kind: "patch"; pinId: number; patch: Partial<PinSummaryResponse> }
  | { kind: "remove"; pinId: number };

/** 룰렛 시트 내부 상태 머신 (설계 §10). */
type RouletteUIState =
  | { status: "idle" }
  | { status: "spinning"; radiusKm: number; candidateCount: number }
  | {
      status: "picked";
      pin: PinSummaryResponse;
      distanceKm: number;
      radiusKm: RouletteRadiusKm;
      candidates: PinSummaryResponse[];
      center: LatLng;
      /**
       * 추첨 당시의 MEMORY 토글 상태. 다음 "다시" 클릭 시 현재 토글과 비교하여
       * 풀 재구성(toggle 변경) vs 동일 풀 재추첨을 분기한다 (FR-REC-6).
       */
      includeMemoryAtPick: boolean;
    }
  | { status: "exhausted" }
  | { status: "geo-error"; message: string };

/** MUST-4: 5분 캐시 정책 (설계 §10). */
const PINS_CACHE_TTL_MS = 5 * 60 * 1000;

/** spin → picked 사이의 짧은 연출 시간 (FR-REC-2). */
const SPIN_DURATION_MS = 700;

/**
 * /map 라우트 클라이언트 컨테이너.
 *
 * 배치 4a 추가:
 *  - 룰렛 시트(`activeSheet === "roulette"`) — Spin → Picked/Exhausted/GeoError
 *  - `useGeolocation` 권한 + 좌표
 *  - 권한 거부 시 PermissionDialog (vertical stack, onMap 오버레이)
 *  - 5분 캐시 정책 (MUST-4): stale 시 BFF 프록시로 핀 목록 재조회
 *  - "지도에서 보기" → `map.flyTo` + popup 자동 표시
 *  - "다시" → 동일 후보 풀에서 재추첨 (FR-REC-6)
 */
export default function MapClient({
  initialPins,
  groupId,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  groupName,
  mapboxToken,
  mapboxStyleUrl,
  myNickname,
  myId,
}: MapClientProps) {
  // 딥링크(?pinId=X) 진입 시 초기 geolocation flyTo를 건너뜀 — 핀 줌인 유지.
  const skipInitialGeoFly = useMemo(
    () => typeof window !== "undefined" && new URLSearchParams(window.location.search).has("pinId"),
    [],
  );

  const [pins, setPins] = useState<PinSummaryResponse[]>(initialPins);
  // MUST-5: 태그/메모 등 부분 갱신을 마커/팝업에 즉시 반영하기 위한 useOptimistic.
  // Phase 2.8: reducer 를 `patch | remove` 액션으로 일반화하여 삭제 흐름(FR-7)도 포괄.
  // transition 종료 시 자동 롤백되어 실패 시 마커가 복원된다 (AC-16).
  const [optimisticPins, applyOptimistic] = useOptimistic<
    PinSummaryResponse[],
    OptimisticAction
  >(pins, (current, action) => {
    if (action.kind === "remove") {
      return current.filter((p) => p.id !== action.pinId);
    }
    return current.map((p) =>
      p.id === action.pinId ? { ...p, ...action.patch } : p,
    );
  });
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [_isOptimisticPending, startOptimisticTransition] = useTransition();
  const [selectedPinId, setSelectedPinId] = useState<number | null>(null);
  const [map, setMap] = useState<mapboxgl.Map | null>(null);
  const [hasCluster, setHasCluster] = useState(false);
  const [mapError, setMapError] = useState<MapLoadErrorReason | null>(null);

  const [activeSheet, setActiveSheet] = useState<ActiveSheet>(null);
  const [addPinOrigin, setAddPinOrigin] = useState<NewPinOrigin | null>(null);

  // 룰렛 관련 상태.
  const { state: geoState, permissionState, request: geoRequest } =
    useGeolocation();
  const [showPermDialog, setShowPermDialog] = useState(false);
  // "나중에" 선택 시 셔플 비활성. 권한이 명시적으로 denied 인 경우에도 true.
  const [rouletteDeferred, setRouletteDeferred] = useState(false);
  const [rouletteState, setRouletteState] = useState<RouletteUIState>({
    status: "idle",
  });
  // FR-REC-6: 룰렛 풀에 MEMORY 핀 포함 여부 (세션 단위, 새로고침 시 OFF 로 복귀, BR-5).
  const [includeMemory, setIncludeMemory] = useState(false);
  // MUST-4: 핀 목록 fetch 시각. initialPins는 page.tsx에서 방금 받은 값.
  // Date.now()는 렌더 중 호출 금지(react-hooks/purity) → mount effect에서 초기화.
  // null 상태에서는 캐시 검사 자체를 skip(=stale로 간주)하여 첫 룰렛은 항상 await 재조회.
  const pinsCacheRef = useRef<{ fetchedAt: number } | null>(null);
  useEffect(() => {
    if (pinsCacheRef.current === null) {
      pinsCacheRef.current = { fetchedAt: Date.now() };
    }
  }, []);
  // 룰렛 트리거 후 권한이 granted로 전이되면 진행하기 위한 플래그.
  const pendingRouletteRef = useRef(false);
  // spin → picked 전환 타이머. 시트 닫힘/언마운트 시 cleanup 하여
  // idle 상태가 다시 picked로 덮어씌워지는 것을 방지한다.
  const spinTimerRef = useRef<number | null>(null);

  // 언마운트 시 spin 타이머 cleanup.
  useEffect(
    () => () => {
      if (spinTimerRef.current !== null) {
        window.clearTimeout(spinTimerRef.current);
        spinTimerRef.current = null;
      }
    },
    [],
  );

  const isDesktop = useMediaQuery("(min-width: 768px)");
  // 모바일 키보드 등장 시 root container 높이를 줄여 ActionBar/Sheet 가
  // 키보드 위에 정확히 정렬되도록 한다. 데스크탑(>=768px)에서는 SidePanel 경로라 무영향.
  const { keyboardHeight, keyboardOpen } = useKeyboardInsets();
  // KBD-2 한 프레임 깜빡임 회피: 키보드 닫힘 전환은 Sheet/컨테이너 transition(150ms) 종료 후
  // ActionBar를 mount하도록 keyboardOpen을 150ms 지연 반영한 파생값을 사용한다.
  // open 전환은 즉시 — 키보드가 올라오자마자 ActionBar unmount하여 입력 공간 확보.
  const [keyboardOpenForLayout, setKeyboardOpenForLayout] =
    useState(keyboardOpen);
  useEffect(() => {
    if (keyboardOpen) {
      setKeyboardOpenForLayout(true);
      return;
    }
    const t = window.setTimeout(() => setKeyboardOpenForLayout(false), 150);
    return () => window.clearTimeout(t);
  }, [keyboardOpen]);

  // Phase 8: 알림 시스템 통합. MapClient 한 곳에서만 호출하여 SSE 중복 구독을 방지한다.
  const notifications = useNotifications();

  // Phase 10: 장소 방문 감지 상태 (설계 §5.6).
  // - shownPinIdsRef: 세션 단위 중복 노출 방지. 토스트가 한 번 노출된 핀은 새로고침 전까지 재노출되지 않는다.
  // - visitedAtRef: 사용자가 "네, 다녀왔어요" 를 누른 시각 — VisitMemoSheet 의 dateLabel 에 사용.
  // - mapboxViewRef: 1차 PATCH 성공 시 marker bounce + confetti 트리거.
  const shownPinIdsRef = useRef<Set<number>>(new Set());
  const {
    evaluate: evaluateVisit,
    clearFirstEnterAt,
    clearAllFirstEnterAt,
  } = useVisitDetection();
  const [visitToastPin, setVisitToastPin] =
    useState<PinSummaryResponse | null>(null);
  const [visitMemoPin, setVisitMemoPin] =
    useState<PinSummaryResponse | null>(null);
  // Phase 10 보강 (AC-VD-14): 1차 PATCH 실패 시 사용자에게 인라인 토스트로 피드백을 준다.
  // null 이면 토스트 비표시. 메시지 set 후 useEffect 가 1.5초 뒤 자동으로 null 로 되돌린다.
  const [visitErrorMessage, setVisitErrorMessage] = useState<string | null>(null);
  // Phase 10 보강 (AC-VD-25, 2026-05-24): 동시 수정으로 본 디바이스 PATCH 가 전환을 발생시키지 못한
  // 케이스에서 confetti/메모 시트 대신 표시할 안내 토스트. 2초 후 자동 닫힘.
  const [visitInfoMessage, setVisitInfoMessage] = useState<string | null>(null);
  const visitedAtRef = useRef<Date | null>(null);
  const mapboxViewRef = useRef<MapboxViewHandle | null>(null);

  // visitErrorMessage 자동 닫힘 (1.5초). 메시지가 바뀌면 이전 타이머 정리 후 재시작.
  useEffect(() => {
    if (!visitErrorMessage) return;
    const t = setTimeout(() => setVisitErrorMessage(null), 1500);
    return () => clearTimeout(t);
  }, [visitErrorMessage]);

  // Phase 10 보강 (AC-VD-25, 2026-05-24): visitInfoMessage 자동 닫힘 (2초).
  useEffect(() => {
    if (!visitInfoMessage) return;
    const t = setTimeout(() => setVisitInfoMessage(null), 2000);
    return () => clearTimeout(t);
  }, [visitInfoMessage]);

  // Phase 10 보강 (AC-VD-24, 2026-05-24): 탭/앱 hidden 동안 firstEnterAt 이 유지되면 깨어났을 때
  // 30초 초과 누적으로 즉시 토스트 발동 가능. visible 진입 시 firstEnterAt 전체를 비운다.
  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        clearAllFirstEnterAt();
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => document.removeEventListener("visibilitychange", onVisibility);
  }, [clearAllFirstEnterAt]);

  // 동시 1개 패널 정책: 다른 액션 시트가 열리면 알림 패널을 닫는다.
  // (역방향 — 알림 패널 열림 시 다른 시트 닫기 — 은 mobileBell/desktopBell onClick에서 처리.)
  useEffect(() => {
    if (activeSheet && notifications.isPanelOpen) {
      notifications.closePanel();
    }
  }, [activeSheet, notifications]);

  // Deep link: URL `?pinId=X` 진입 시 해당 핀 자동 선택 + flyTo. 그룹 멤버만 적용됨.
  const deepLinkAppliedRef = useRef(false);
  useEffect(() => {
    if (deepLinkAppliedRef.current) return;
    if (!map || optimisticPins.length === 0) return;
    const params = new URLSearchParams(window.location.search);
    const pinIdStr = params.get("pinId");
    if (!pinIdStr) {
      deepLinkAppliedRef.current = true;
      return;
    }
    const pinId = Number(pinIdStr);
    if (Number.isNaN(pinId)) {
      deepLinkAppliedRef.current = true;
      return;
    }
    const target = optimisticPins.find((p) => p.id === pinId);
    if (!target) {
      // 핀 없거나 다른 그룹 → 무시 (사용자 안내 없이 일반 진입)
      deepLinkAppliedRef.current = true;
      return;
    }
    setSelectedPinId(pinId);
    map.flyTo({
      center: [target.longitude, target.latitude],
      zoom: 15,
      duration: 700,
    });
    deepLinkAppliedRef.current = true;
  }, [map, optimisticPins]);

  // 그룹 핀 30s polling — 다른 사용자가 등록한 신규 핀만 append.
  // append-only 정책: 본인 in-flight 액션(add/patch/remove)과의 race를 회피하고
  // 다른 사용자의 수정/삭제는 새로고침 전까지 미반영(후속 PR에서 충돌 정책과 함께 다룸).
  useGroupPinSync({
    groupId,
    onTick: (serverPins) => {
      setPins((prev) => {
        const localIds = new Set(prev.map((p) => p.id));
        const newOnly = serverPins.filter((p) => !localIds.has(p.id));
        return newOnly.length === 0 ? prev : [...prev, ...newOnly];
      });
    },
  });

  const handleMarkerClick = useCallback((pinId: number) => {
    setSelectedPinId(pinId);
  }, []);

  const handleMapReady = useCallback((next: mapboxgl.Map) => {
    setMap(next);
  }, []);

  const handleClustersChange = useCallback(
    ({ hasCluster: hc }: { hasCluster: boolean }) => {
      // 동일 값일 때 React가 리렌더를 skip하도록 functional setState로 안전화.
      // (renderClusters가 매 viewport 변경마다 호출되어 set-state 폭주 방지)
      setHasCluster((prev) => (prev === hc ? prev : hc));
    },
    [],
  );

  /**
   * MUST-5: 정보창 ⋮ 인라인 태그 칩 토글.
   * useOptimistic 으로 마커 모양 즉시 반영 → updatePinTagAction 호출 →
   * 성공 시 서버 응답으로 pins state 갱신 (revalidate 없음, MUST-1).
   * 실패 시 useOptimistic 은 다음 commit 에서 자동 롤백되고
   * PinPopup 내부 인라인 에러로 메시지를 노출한다.
   */
  const handleTagChange = useCallback(
    (
      pinId: number,
      nextTag: PinTag,
    ): Promise<{ ok: boolean; message?: string }> => {
      // React 19: startTransition은 async callback을 정식 지원한다.
      // await 동안 transition이 유지되어 useOptimistic 상태도 보존됨 → 즉시 롤백 깜빡임 방지.
      return new Promise((resolve) => {
        startOptimisticTransition(async () => {
          applyOptimistic({ kind: "patch", pinId, patch: { tag: nextTag } });
          const result = await updatePinTagAction(groupId, pinId, nextTag);
          if (result.ok) {
            // Phase 10 보강: UpdatePinResponse — summary 만 사용한다.
            setPins((prev) =>
              prev.map((p) => (p.id === pinId ? result.data.summary : p)),
            );
            resolve({ ok: true });
            return;
          }
          const message =
            result.code === "GROUP_NOT_MEMBER"
              ? "권한이 없어요"
              : result.message;
          resolve({ ok: false, message });
        });
      });
    },
    [applyOptimistic, groupId],
  );

  /**
   * FR-MMO-2: PinPopup ⋮ 펼침 "메모" 탭에서 메모 저장 콜백.
   * useOptimistic 으로 팝업 메모 본문을 즉시 갱신 → updatePinMemoAction 호출 →
   * 성공 시 서버 응답으로 pins state 갱신 + 캐시 fetchedAt 갱신.
   * 실패 시 PinPopupMemoEditor 의 내부 입력 state 가 보존되고 에러 박스만 노출된다.
   */
  const handleMemoChange = useCallback(
    (
      pinId: number,
      nextMemo: string,
    ): Promise<{ ok: boolean; message?: string }> => {
      return new Promise((resolve) => {
        startOptimisticTransition(async () => {
          applyOptimistic({ kind: "patch", pinId, patch: { memo: nextMemo } });
          const result = await updatePinMemoAction(groupId, pinId, nextMemo);
          if (result.ok) {
            // Phase 10 보강: UpdatePinResponse — summary 만 사용한다.
            setPins((prev) =>
              prev.map((p) => (p.id === pinId ? result.data.summary : p)),
            );
            if (pinsCacheRef.current) {
              pinsCacheRef.current.fetchedAt = Date.now();
            }
            resolve({ ok: true });
            return;
          }
          const message =
            result.code === "GROUP_NOT_MEMBER"
              ? "권한이 없어요"
              : result.code === "PIN_MEMO_TOO_LONG"
                ? "메모는 500자까지 입력할 수 있어요"
                : result.code === "PIN_MEMO_INVALID"
                  ? "메모 값이 유효하지 않아요"
                  : result.code === "PIN_NOT_FOUND"
                    ? "이 핀을 찾을 수 없어요"
                    : result.message;
          resolve({ ok: false, message });
        });
      });
    },
    [applyOptimistic, groupId],
  );

  /** 핀 장소 이름 변경. useOptimistic + updatePinPlaceNameAction. */
  const handlePlaceNameChange = useCallback(
    (
      pinId: number,
      nextPlaceName: string,
    ): Promise<{ ok: boolean; message?: string }> => {
      return new Promise((resolve) => {
        startOptimisticTransition(async () => {
          applyOptimistic({
            kind: "patch",
            pinId,
            patch: { placeName: nextPlaceName },
          });
          const result = await updatePinPlaceNameAction(
            groupId,
            pinId,
            nextPlaceName,
          );
          if (result.ok) {
            // Phase 10 보강: UpdatePinResponse — summary 만 사용한다.
            setPins((prev) =>
              prev.map((p) => (p.id === pinId ? result.data.summary : p)),
            );
            if (pinsCacheRef.current) {
              pinsCacheRef.current.fetchedAt = Date.now();
            }
            resolve({ ok: true });
            return;
          }
          const message =
            result.code === "GROUP_NOT_MEMBER"
              ? "권한이 없어요"
              : result.code === "PIN_NOT_FOUND"
                ? "이 핀을 찾을 수 없어요"
                : result.message;
          resolve({ ok: false, message });
        });
      });
    },
    [applyOptimistic, groupId],
  );

  // Phase 2.8 FR-7: 삭제 흐름 상태.
  // - deleteCandidate: 모달 표시 대상 핀 (null 이면 모달 닫힘).
  // - deleteErrorByPinId: 핀별 직전 실패 메시지 (AC-16). 자동 롤백 후
  //   재선택된 PinPopup 의 deleteError prop 으로 전달되어 인라인 노출.
  const [deleteCandidate, setDeleteCandidate] =
    useState<PinSummaryResponse | null>(null);
  const [deleteErrorByPinId, setDeleteErrorByPinId] = useState<
    Record<number, string>
  >({});

  // Phase 2.10 B4b: 좌표 수정 흐름 상태.
  // - coordinateEditTarget: 좌표 수정 picker 대상 핀.
  // - coordinateErrorByPinId: 핀별 직전 좌표 변경 실패 메시지 (자동 롤백 후 인라인 표시).
  const [coordinateEditTarget, setCoordinateEditTarget] =
    useState<PinSummaryResponse | null>(null);
  const [coordinateErrorByPinId, setCoordinateErrorByPinId] = useState<
    Record<number, string>
  >({});

  /**
   * AC-15/16/17: 모달 확인 → optimistic remove → server action.
   * 1) 모달 닫기 (낙관적 흐름 시작 전)
   * 2) startOptimisticTransition 안에서 remove 액션 + deletePinAction 호출
   * 3) 성공: 실제 pins state 에서 제거 + selectedPinId 해제
   * 4) 실패: 핀별 에러 저장 + 동일 핀 재선택 → transition 종료 시 자동 롤백 →
   *    마커 복원 + PinPopup 재mount → deleteError 인라인 표시
   */
  const handleConfirmDelete = useCallback(() => {
    if (!deleteCandidate) return;
    const pinId = deleteCandidate.id;
    setDeleteCandidate(null);

    startOptimisticTransition(async () => {
      applyOptimistic({ kind: "remove", pinId });
      const result = await deletePinAction(groupId, pinId);
      if (result.ok) {
        setPins((prev) => prev.filter((p) => p.id !== pinId));
        setSelectedPinId(null);
        // 직전 실패가 남긴 orphan 에러 키를 정리한다.
        // 키가 없으면 동일 참조를 유지하여 불필요한 렌더를 방지.
        setDeleteErrorByPinId((prev) => {
          if (!(pinId in prev)) return prev;
          const { [pinId]: _omit, ...rest } = prev;
          return rest;
        });
        return;
      }
      const message =
        result.code === "GROUP_NOT_MEMBER"
          ? "권한이 없어요"
          : result.code === "PIN_NOT_FOUND"
            ? "이 핀을 찾을 수 없어요"
            : result.message;
      setDeleteErrorByPinId((prev) => ({ ...prev, [pinId]: message }));
      setSelectedPinId(pinId);
    });
  }, [deleteCandidate, groupId, applyOptimistic]);

  /**
   * Phase 2.10 B4b: 좌표 수정 진입 핸들러.
   * - 해당 pin 의 기존 에러 클리어
   * - popup 닫고 (M5) picker 시트 오픈
   * - 깜빡임 완화를 위해 지도 중심을 핀 위치로 flyTo (M4)
   */
  const handleRequestCoordinateEdit = useCallback(
    (pin: PinSummaryResponse) => {
      setCoordinateErrorByPinId((prev) => {
        if (!(pin.id in prev)) return prev;
        const next = { ...prev };
        delete next[pin.id];
        return next;
      });
      setSelectedPinId(null);
      setCoordinateEditTarget(pin);
      setActiveSheet("coordinate-edit");
      if (map) {
        map.flyTo({
          center: [Number(pin.longitude), Number(pin.latitude)],
          zoom: 16,
        });
      }
    },
    [map],
  );

  /**
   * 좌표 수정 완료: optimistic patch + server action.
   * 성공 시 pins state 갱신 + 캐시 fetchedAt 갱신.
   * 실패 시 핀별 에러 저장 + 동일 핀 재선택 → 자동 롤백 + 인라인 에러.
   */
  const handleConfirmCoordinateEdit = useCallback(
    ({ lat, lng }: { lat: number; lng: number }) => {
      const target = coordinateEditTarget;
      if (!target) return;
      const pinId = target.id;
      setActiveSheet(null);
      setCoordinateEditTarget(null);

      // 백엔드 검증: lat/lng scale ≤ 7. Mapbox center는 15+자리라 그대로 보내면 INVALID.
      const roundedLat = Number(lat.toFixed(7));
      const roundedLng = Number(lng.toFixed(7));
      startOptimisticTransition(async () => {
        applyOptimistic({
          kind: "patch",
          pinId,
          patch: { latitude: roundedLat, longitude: roundedLng },
        });
        setSelectedPinId(pinId);
        const result = await updatePinCoordinateAction(
          groupId,
          pinId,
          roundedLat,
          roundedLng,
        );
        if (result.ok) {
          // Phase 10 보강: UpdatePinResponse — summary 만 사용한다.
          setPins((prev) =>
            prev.map((p) => (p.id === pinId ? result.data.summary : p)),
          );
          if (pinsCacheRef.current) {
            pinsCacheRef.current.fetchedAt = Date.now();
          }
          // 직전 실패가 남긴 orphan 에러 키 정리.
          setCoordinateErrorByPinId((prev) => {
            if (!(pinId in prev)) return prev;
            const { [pinId]: _omit, ...rest } = prev;
            return rest;
          });
          return;
        }
        const message =
          result.code === "PIN_COORDINATE_INVALID"
            ? "좌표가 유효한 범위를 벗어났어요"
            : result.code === "GROUP_NOT_MEMBER"
              ? "권한이 없어요"
              : result.code === "PIN_NOT_FOUND"
                ? "이 핀을 찾을 수 없어요"
                : result.message;
        setCoordinateErrorByPinId((prev) => ({ ...prev, [pinId]: message }));
        setSelectedPinId(pinId);
      });
    },
    [coordinateEditTarget, groupId, applyOptimistic],
  );

  /**
   * 좌표 수정 취소: picker 닫고 원래 popup 복귀.
   */
  const handleCancelCoordinateEdit = useCallback(() => {
    const pinId = coordinateEditTarget?.id ?? null;
    setActiveSheet(null);
    setCoordinateEditTarget(null);
    if (pinId !== null) {
      setSelectedPinId(pinId);
    }
  }, [coordinateEditTarget]);

  /**
   * 좌표 + 핀 목록을 받아 추첨을 수행하고 결과를 상태에 반영.
   * 추첨 직전 5분 캐시 정책으로 stale이면 await 재조회 (MUST-4).
   *
   * `tagsAllowed`로 풀 필터를 외부에서 제어한다. MEMORY 토글 ON 이면
   * `["REEL", "WISH", "MEMORY"]`를, OFF 이면 `["REEL", "WISH"]`를 전달한다 (FR-REC-6).
   * 호출처에서는 `computeTagsAllowed(includeMemory)` 헬퍼로 일관성을 보장한다.
   */
  const runRoulette = useCallback(
    async (center: LatLng, tagsAllowed: PinTag[]) => {
      // 최신 핀 풀: stale이면 재조회 후 결과 사용.
      let pool = pins;
      const cache = pinsCacheRef.current;
      const isStale =
        cache === null || Date.now() - cache.fetchedAt >= PINS_CACHE_TTL_MS;
      if (isStale) {
        try {
          const res = await apiFetch<PinListResponse>(
            `/groups/${groupId}/pins`,
          );
          pool = res.items;
          setPins(pool);
          pinsCacheRef.current = { fetchedAt: Date.now() };
        } catch (e) {
          // 재조회 실패 시 기존 캐시로 진행 (룰렛은 그래도 동작 가능).
          console.error("roulette: listPins refresh failed", e);
        }
      }

      const result = pickRandomWithExpansion(center, pool, tagsAllowed);
      if (result.kind === "exhausted") {
        setRouletteState({ status: "exhausted" });
        return;
      }
      // 짧은 spin 연출 후 picked 전이 (M-6 → M-6c).
      // 추첨 당시의 토글 상태를 stash 하여 다음 "다시" 클릭 시 풀 재구성 여부를 분기한다.
      const includeMemoryAtPick = tagsAllowed.includes("MEMORY");
      setRouletteState({
        status: "spinning",
        radiusKm: result.radiusKm,
        candidateCount: result.candidateCount,
      });
      if (spinTimerRef.current !== null) {
        window.clearTimeout(spinTimerRef.current);
      }
      spinTimerRef.current = window.setTimeout(() => {
        spinTimerRef.current = null;
        setRouletteState({
          status: "picked",
          pin: result.pin,
          distanceKm: result.distanceKm,
          radiusKm: result.radiusKm,
          candidates: result.candidates,
          center,
          includeMemoryAtPick,
        });
      }, SPIN_DURATION_MS);
    },
    [groupId, pins],
  );

  /**
   * 셔플 탭 진입점. 권한 상태에 따라 다이얼로그/요청/즉시추첨 분기.
   *
   * Permissions API 가 이미 granted 임을 알려주면 모달을 건너뛰고
   * 좌표 fetch만 한 뒤 룰렛을 진행한다 (UX 개선: 첫 셔플에서 모달 깜빡임 방지).
   */
  const handleRouletteTap = useCallback(() => {
    setSelectedPinId(null);
    setActiveSheet("roulette");

    if (geoState.status === "denied" || permissionState === "denied") {
      // 명시적 거부 상태: 다이얼로그 재안내.
      setShowPermDialog(true);
      return;
    }
    if (geoState.status === "granted") {
      setRouletteState({
        status: "spinning",
        radiusKm: 10,
        candidateCount: 0,
      });
      void runRoulette(
        geoState.coords,
        computeTagsAllowed(includeMemory),
      );
      return;
    }
    if (geoState.status === "unavailable") {
      setRouletteState({
        status: "geo-error",
        message: "이 브라우저에서는 위치를 사용할 수 없어요.",
      });
      return;
    }
    if (geoState.status === "timeout") {
      // 재시도: 다시 요청.
      pendingRouletteRef.current = true;
      geoRequest();
      setRouletteState({
        status: "spinning",
        radiusKm: 10,
        candidateCount: 0,
      });
      return;
    }
    // permission이 이미 granted 라면 모달 우회하여 즉시 좌표 fetch.
    if (permissionState === "granted") {
      pendingRouletteRef.current = true;
      geoRequest();
      setRouletteState({
        status: "spinning",
        radiusKm: 10,
        candidateCount: 0,
      });
      return;
    }
    // idle 또는 prompting + permission이 prompt/unknown: 사전 다이얼로그 안내.
    setShowPermDialog(true);
  }, [geoState, permissionState, geoRequest, runRoulette, includeMemory]);

  // geoState 전이 감지: pendingRouletteRef가 true면 진행.
  //
  // 모든 후속 상태 전이는 setTimeout(0)으로 micro-task 분리하여
  // effect 내부 직접 setState로 인한 cascading render 경고를 회피한다.
  // useGeolocation이 외부 시스템(navigator.geolocation)이므로
  // 그 응답을 React 상태에 반영하는 어댑터 effect로서 의도된 패턴이다.
  useEffect(() => {
    if (!pendingRouletteRef.current) return;
    if (geoState.status === "idle" || geoState.status === "prompting") return;
    pendingRouletteRef.current = false;
    const status = geoState.status;
    const handle = window.setTimeout(() => {
      if (status === "granted") {
        void runRoulette(
          geoState.coords,
          computeTagsAllowed(includeMemory),
        );
      } else if (status === "denied") {
        setShowPermDialog(true);
        setRouletteState({ status: "idle" });
        if (spinTimerRef.current !== null) {
          window.clearTimeout(spinTimerRef.current);
          spinTimerRef.current = null;
        }
      } else if (status === "unavailable") {
        setRouletteState({
          status: "geo-error",
          message:
            "위치 정보를 받지 못했어요. macOS는 시스템 설정 > 개인정보 보호 > 위치 서비스에서 브라우저 항목이 켜져 있어야 해요. WiFi가 꺼져 있어도 실패할 수 있어요.",
        });
      } else if (status === "timeout") {
        setRouletteState({
          status: "geo-error",
          message: "위치 확인이 어려워요. 잠시 후 다시 시도해 주세요.",
        });
      }
    }, 0);
    return () => window.clearTimeout(handle);
  }, [geoState, runRoulette, includeMemory]);

  /**
   * 메모/검색 흐름에서 적용한 flyTo padding 을 초기화한다.
   * Mapbox 의 padding 은 다음 명시적 변경 전까지 지속되어 map.getCenter() 결과가
   * 시각적 viewport 중앙이 아닌 optical center 로 어긋난다.
   * 이 어긋남이 크로스헤어(+) 흐름에서 좌표 mismatch 를 유발하므로 흐름 종료/시작 시 매번 초기화.
   */
  const resetMapPadding = useCallback(() => {
    if (!map) return;
    map.setPadding({ top: 0, bottom: 0, left: 0, right: 0 });
  }, [map]);

  // 액션바/사이드바 탭 변경: 같은 탭 재클릭 시 닫기 토글.
  const handleTabChange = useCallback(
    (tab: Exclude<ActionBarTab, null>) => {
      setSelectedPinId(null);
      if (tab === "roulette") {
        // 룰렛 탭 토글: 이미 룰렛 시트가 열려 있으면 닫기.
        if (activeSheet === "roulette") {
          setActiveSheet(null);
          setRouletteState({ status: "idle" });
          pendingRouletteRef.current = false;
          if (spinTimerRef.current !== null) {
            window.clearTimeout(spinTimerRef.current);
            spinTimerRef.current = null;
          }
          return;
        }
        handleRouletteTap();
        return;
      }
      setActiveSheet((prev) => {
        if (tab === "search") return prev === "search" ? null : "search";
        if (tab === "add") return prev === "add" ? null : "add";
        return prev;
      });
      if (tab === "add") {
        setAddPinOrigin(null);
        // + 탭 진입 시 직전 메모/검색 흐름의 padding 을 반드시 초기화한다.
        // 그렇지 않으면 map.getCenter() 가 optical center 를 반환하여 크로스헤어 시각 중앙과
        // 어긋난 좌표가 AddPinPickerContent 로 전달되고 결국 다른 위치가 저장된다.
        resetMapPadding();
        // + 탭 진입 시 너무 줌아웃되어 있으면 현재 위치 기준으로 줌인 이동.
        // 좌표 picker UX 개선 — 사용자가 핀 위치를 정확히 찍기 쉽도록.
        if (map && map.getZoom() < 13) {
          if (geoState.status === "granted") {
            map.flyTo({
              center: [geoState.coords.lng, geoState.coords.lat],
              zoom: 15,
            });
          } else if (typeof navigator !== "undefined" && navigator.geolocation) {
            navigator.geolocation.getCurrentPosition(
              (pos) => {
                map.flyTo({
                  center: [pos.coords.longitude, pos.coords.latitude],
                  zoom: 15,
                });
              },
              () => {
                // 위치 잡기 실패 시 현재 지도 중심을 유지하면서 줌만 살짝 올림.
                map.flyTo({ zoom: 14 });
              },
              { timeout: 5000, maximumAge: 60000 },
            );
          } else {
            map.flyTo({ zoom: 14 });
          }
        }
      }
    },
    [activeSheet, handleRouletteTap, map, geoState, resetMapPadding],
  );

  /**
   * 모바일(<768px)에서 메모 Sheet가 화면 하단을 덮을 때 마커가 보이는 영역의 중앙에 오도록
   * flyTo padding 값을 계산한다. Mapbox는 padding 만큼을 비표시 영역으로 간주하고
   * 나머지 영역 중앙에 center를 배치한다.
   */
  const computeMemoFlyToPadding = useCallback(():
    | { top?: number; bottom?: number; left?: number; right?: number }
    | undefined => {
    if (typeof window === "undefined") return undefined;
    if (isDesktop) return undefined;
    // Sheet 높이를 매번 측정하기 어려우므로 viewport 의 55% 로 근사.
    // 핀이 viewport 상단 22.5% 부근에 위치하게 되어 Sheet 와 가시 영역 모두에서 균형이 좋다.
    return { bottom: Math.round(window.innerHeight * 0.55) };
  }, [isDesktop]);

  // 검색에서 장소 선택 → MemoTag 단계로 전이 + 해당 좌표로 카메라 이동
  const handleSelectPlace = useCallback(
    (place: PlaceSearchItem) => {
      setAddPinOrigin({
        placeName: place.placeName,
        address: place.address,
        latitude: place.latitude,
        longitude: place.longitude,
        editable: false,
      });
      setActiveSheet("memo");
      if (map) {
        map.flyTo({
          center: [Number(place.longitude), Number(place.latitude)],
          zoom: 15,
          padding: computeMemoFlyToPadding(),
        });
      }
    },
    [map, computeMemoFlyToPadding],
  );

  // Crosshair에서 좌표 확정 → MemoTag 단계로 전이.
  // reverse geocoding 으로 채워진 placeName/address 를 초기값으로 사용하되,
  // editable=true 로 사용자가 placeName 을 자유롭게 수정할 수 있도록 유지한다.
  const handleConfirmCrosshair = useCallback(
    (origin: {
      lng: number;
      lat: number;
      address: string | null;
      placeName: string | null;
    }) => {
      setAddPinOrigin({
        placeName: origin.placeName ?? "",
        address: origin.address,
        latitude: origin.lat,
        longitude: origin.lng,
        editable: true,
      });
      setActiveSheet("memo");
      // 모바일에서는 Sheet가 화면 하단을 덮으므로 미리보기 마커가 보이는 영역의 중앙에 오도록 재정렬.
      if (map) {
        map.flyTo({
          center: [origin.lng, origin.lat],
          padding: computeMemoFlyToPadding(),
        });
      }
    },
    [map, computeMemoFlyToPadding],
  );

  const handleCancelMemo = useCallback(() => {
    setActiveSheet(null);
    setAddPinOrigin(null);
    resetMapPadding();
  }, [resetMapPadding]);

  // 저장 성공 → 클라 state 에 직접 추가 (revalidate 없음, MUST-1) + 새 핀 위치로 카메라 이동.
  const handlePinCreated = useCallback(
    (newPin: PinSummaryResponse) => {
      setPins((prev) => [...prev, newPin]);
      // 캐시 fetchedAt도 갱신: 방금 만든 핀까지 포함하는 fresh 상태.
      if (pinsCacheRef.current) {
        pinsCacheRef.current.fetchedAt = Date.now();
      }
      setActiveSheet(null);
      setAddPinOrigin(null);
      setSelectedPinId(newPin.id);
      if (map) {
        // 메모 단계의 padding 을 0 으로 되돌리며 새 핀 위치로 이동.
        map.flyTo({
          center: [Number(newPin.longitude), Number(newPin.latitude)],
          zoom: 15,
          padding: { top: 0, bottom: 0, left: 0, right: 0 },
        });
      }
    },
    [map],
  );

  const handleSheetClose = useCallback(() => {
    // coordinate-edit 시트를 × 로 닫으면 취소와 동일하게 처리
    if (activeSheet === "coordinate-edit" && coordinateEditTarget) {
      setSelectedPinId(coordinateEditTarget.id);
    }
    setActiveSheet(null);
    setAddPinOrigin(null);
    setCoordinateEditTarget(null);
    // Phase 10: visit-memo 시트를 × 로 닫으면 건너뛰기와 동일 — 2차 PATCH 미발사.
    setVisitMemoPin(null);
    setRouletteState({ status: "idle" });
    pendingRouletteRef.current = false;
    if (spinTimerRef.current !== null) {
      window.clearTimeout(spinTimerRef.current);
      spinTimerRef.current = null;
    }
    // 메모/검색 흐름에서 적용한 padding 을 초기화하여 다음 + 흐름의 크로스헤어 좌표가
    // 시각적 viewport 중앙과 일치하도록 보장한다.
    resetMapPadding();
  }, [activeSheet, coordinateEditTarget, resetMapPadding]);

  /**
   * Phase 10: GeolocateControl `geolocate` 이벤트 콜백 (설계 §5.6).
   *
   * MapboxView 가 GeolocateControl 콜백에서 항상 호출한다. 본 핸들러는
   * useVisitDetection.evaluate 를 거쳐 detectedPinId 가 있고 다른 패널/시트/토스트/메모시트/알림패널
   * 이 모두 닫혀 있을 때만 VisitToast 를 노출한다 (동시 1개 패널 정책, QE-VD-1).
   *
   * 패널이 열려 있어 토스트를 띄우지 못한 경우에도 firstEnterAt 은 useVisitDetection 내부에서
   * 누적되므로 다음 geolocate 콜백에서 다시 시도된다.
   */
  // Phase 10 성능: WISH/REEL 핀만 미리 필터링하여 useVisitDetection 에 전달.
  // optimisticPins 변경 시에만 재계산되며 evaluate 호출마다의 중복 필터링을 제거.
  const wishReelPins = useMemo(
    () =>
      optimisticPins.filter((p) => p.tag === "WISH" || p.tag === "REEL"),
    [optimisticPins],
  );

  const handleGeolocate = useCallback(
    (position: GeolocationPosition) => {
      const { detectedPinId } = evaluateVisit({
        position,
        wishReelPins,
        shownPinIds: shownPinIdsRef.current,
      });
      if (detectedPinId === null) return;
      if (
        activeSheet !== null ||
        visitToastPin !== null ||
        visitMemoPin !== null ||
        notifications.isPanelOpen
      ) {
        return;
      }
      const pin = wishReelPins.find((p) => p.id === detectedPinId);
      if (!pin) return;
      setVisitToastPin(pin);
    },
    [
      evaluateVisit,
      wishReelPins,
      activeSheet,
      visitToastPin,
      visitMemoPin,
      notifications.isPanelOpen,
    ],
  );

  // Phase 10 자동 폴링 (PRD §11 (b) 후속 튜닝 후보 일부 구현):
  // GeolocateControl 의 trackUserLocation watchPosition 은 정지 시 콜백이 드물게 발화하여
  // 30초 머무름 측정이 사실상 어렵다. 위치 권한이 이미 'granted' 인 경우에 한해
  // 5초마다 강제로 navigator.geolocation.getCurrentPosition 을 호출해 evaluate 를 트리거한다.
  // 'prompt'/'denied' 상태에서는 폴링을 시작하지 않아 iOS 의 자동 권한 다이얼로그 발생을 회피한다.
  const handleGeolocateRef = useRef(handleGeolocate);
  useEffect(() => {
    handleGeolocateRef.current = handleGeolocate;
  }, [handleGeolocate]);

  useEffect(() => {
    if (
      typeof navigator === "undefined" ||
      !navigator.geolocation ||
      !navigator.permissions
    ) {
      return;
    }
    let cancelled = false;
    let intervalId: ReturnType<typeof setInterval> | undefined;

    const startPolling = () => {
      if (intervalId !== undefined) return;
      intervalId = setInterval(() => {
        navigator.geolocation.getCurrentPosition(
          (pos) => handleGeolocateRef.current(pos),
          undefined,
          { enableHighAccuracy: true, timeout: 5_000, maximumAge: 0 },
        );
      }, 5_000);
    };

    const stopPolling = () => {
      if (intervalId !== undefined) {
        clearInterval(intervalId);
        intervalId = undefined;
      }
    };

    navigator.permissions
      .query({ name: "geolocation" as PermissionName })
      .then((status) => {
        if (cancelled) return;
        if (status.state === "granted") startPolling();
        status.addEventListener("change", () => {
          if (cancelled) return;
          if (status.state === "granted") startPolling();
          else stopPolling();
        });
      })
      .catch(() => {
        // Permissions API 미지원/오류 — 폴링 시작 안 함. watchPosition 콜백에만 의존.
      });

    return () => {
      cancelled = true;
      stopPolling();
    };
  }, []);

  /**
   * "다음에 올게요" — 세션 Set 에 추가 후 토스트 닫기 (FR-VD-13).
   * firstEnterAt 도 함께 비워 동일 핀이 즉시 재계산되지 않도록 한다.
   */
  const handleVisitSkip = useCallback(() => {
    if (visitToastPin) {
      shownPinIdsRef.current.add(visitToastPin.id);
      clearFirstEnterAt(visitToastPin.id);
    }
    setVisitToastPin(null);
  }, [visitToastPin, clearFirstEnterAt]);

  /**
   * "네, 다녀왔어요" — 1차 PATCH(tag → MEMORY) 발사 (FR-VD-14, FR-VD-15).
   * - 토스트는 즉시 닫는다 (사용자 피드백).
   * - useOptimistic 으로 마커 모양 즉시 갱신 → updatePinTagAction 호출.
   * - 성공: shownPinIds 추가, firstEnterAt 비움, marker bounce + confetti 트리거,
   *   VisitMemoSheet 오픈 (visit-memo activeSheet).
   * - 실패 (FR-VD-21): 세션 Set 미추가하여 사용자가 다시 시도할 수 있도록 한다.
   *   별도 시스템 에러 UX 인프라가 없어 console.error 만 남긴다.
   */
  const handleVisitConfirm = useCallback(() => {
    if (!visitToastPin) return;
    const pin = visitToastPin;
    const pinId = pin.id;
    visitedAtRef.current = new Date();
    setVisitToastPin(null);

    // Phase 10 UX 개선: 사용자가 줌아웃/줌인 상태일 때 confetti 가 화면 밖에서 발사되지
    // 않도록 핀 위치로 flyTo 후 confetti 를 띄운다. flyTo 와 PATCH 를 병렬 시작하여
    // 둘 다 완료된 시점에 confetti + 시트를 노출한다.
    const flyToPromise = new Promise<void>((resolve) => {
      if (!map) {
        resolve();
        return;
      }
      let settled = false;
      const settle = () => {
        if (settled) return;
        settled = true;
        map.off("moveend", settle);
        resolve();
      };
      map.once("moveend", settle);
      map.flyTo({
        center: [Number(pin.longitude), Number(pin.latitude)],
        zoom: Math.max(map.getZoom(), 16),
        essential: true,
        duration: 1500,
      });
      // safety: flyTo 가 어떤 이유로 moveend 를 발화 안 하더라도 강제 해소.
      setTimeout(settle, 2200);
    });

    startOptimisticTransition(async () => {
      applyOptimistic({ kind: "patch", pinId, patch: { tag: "MEMORY" } });
      // PATCH 와 flyTo 병렬 — 둘 다 완료 후 confetti 발사.
      const [result] = await Promise.all([
        updatePinTagAction(groupId, pinId, "MEMORY"),
        flyToPromise,
      ]);
      if (!result.ok) {
        // FR-VD-21: 시스템 에러 — 세션 Set 미추가, firstEnterAt 도 유지하여 재시도 가능하게 둠.
        // gemini-code-assist 권고: 운영 브라우저 콘솔에 내부 식별자(groupId/pinId)를 노출하지 않는다.
        // 에러 코드만 남겨 디버깅 단서는 보존하고 사용자 식별/리소스 노출은 회피.
        console.error("visit PATCH(tag=MEMORY) failed", result.code);
        // AC-VD-14: 사용자에게 인라인 토스트로 실패를 알린다 (1.5초 후 자동 닫힘).
        setVisitErrorMessage("장소를 추억으로 옮기지 못했어요. 다시 시도해주세요.");
        return;
      }
      // Phase 10 보강 (2026-05-24): UpdatePinResponse — summary + transitionedToMemoryNow.
      const updatedPin = result.data.summary;
      const transitioned = result.data.transitionedToMemoryNow;
      setPins((prev) =>
        prev.map((p) => (p.id === pinId ? updatedPin : p)),
      );
      shownPinIdsRef.current.add(pinId);
      clearFirstEnterAt(pinId);

      if (transitioned) {
        // 정상 케이스 — 본 디바이스에서 전환 발생: confetti + 메모 시트 (FR-VD-15, FR-VD-16).
        // flyTo 가 끝나고 사용자가 핀 위치를 인지할 짧은 호흡을 위해 250ms pause 후 발사.
        await new Promise<void>((resolve) => setTimeout(resolve, 250));
        mapboxViewRef.current?.triggerVisitCelebration(pinId);
        setVisitMemoPin(updatedPin);
        setActiveSheet("visit-memo");
      } else {
        // AC-VD-25: 짝꿍이 먼저 메모리로 전환한 핀 — confetti/시트 스킵 + 안내 토스트.
        setVisitInfoMessage("이미 추억으로 기록된 곳이에요");
      }
    });
  }, [visitToastPin, groupId, applyOptimistic, clearFirstEnterAt, map]);

  /**
   * 메모 저장 — 2차 PATCH(memo) (FR-VD-17 ~ FR-VD-19).
   * 성공: 시트 닫기 + pins state 갱신.
   * 실패 (FR-VD-22): 시트 유지, VisitMemoSheet 내부에서 인라인 에러로 노출.
   */
  const handleVisitMemoSave = useCallback(
    async (memo: string): Promise<{ ok: boolean; message?: string }> => {
      if (!visitMemoPin) return { ok: true };
      const pinId = visitMemoPin.id;
      const result = await updatePinMemoAction(groupId, pinId, memo);
      if (result.ok) {
        // Phase 10 보강: UpdatePinResponse — summary 만 사용한다.
        setPins((prev) =>
          prev.map((p) => (p.id === pinId ? result.data.summary : p)),
        );
        setVisitMemoPin(null);
        setActiveSheet(null);
        // Phase 10 UX 개선: 저장 직후 핀 상세 팝업 자동 표시 → 사용자가 전환 결과를 즉시 확인.
        setSelectedPinId(pinId);
        return { ok: true };
      }
      const message =
        result.code === "GROUP_NOT_MEMBER"
          ? "권한이 없어요"
          : result.code === "PIN_MEMO_TOO_LONG"
            ? "메모는 500자까지 입력할 수 있어요"
            : result.code === "PIN_MEMO_INVALID"
              ? "메모 값이 유효하지 않아요"
              : result.code === "PIN_NOT_FOUND"
                ? "이 핀을 찾을 수 없어요"
                : result.message;
      return { ok: false, message };
    },
    [visitMemoPin, groupId],
  );

  /**
   * 메모 건너뛰기 — 2차 PATCH 미발사. 시트만 닫고 핀 상세 팝업을 자동 표시한다.
   * (FR-VD-20 + Phase 10 UX 개선: 전환 결과 즉시 확인)
   */
  const handleVisitMemoSkip = useCallback(() => {
    const pinId = visitMemoPin?.id;
    setVisitMemoPin(null);
    setActiveSheet(null);
    if (pinId != null) {
      setSelectedPinId(pinId);
    }
  }, [visitMemoPin]);

  /**
   * Phase 8: 알림 패널 핀 아이템 선택 → 지도 이동 + (가능 시) PinPopup 자동 표시.
   *
   * <p>패널 자체는 `NotificationPanel` 내부에서 onSelectPin 직후 onClose로 닫힌다.
   * 삭제된 핀(deleted=true) 또는 좌표 없음(null)이면 no-op.
   * 클라이언트 state에 존재하는 핀이면 setSelectedPinId로 PinPopup 자동 표시 —
   * 룰렛 "지도에서 보기" 패턴과 동일.</p>
   */
  const handleSelectPinFromNotification = useCallback(
    (pin: NotificationPinItem) => {
      if (pin.deleted || pin.latitude == null || pin.longitude == null) return;
      if (map) {
        map.flyTo({
          center: [Number(pin.longitude), Number(pin.latitude)],
          zoom: 14,
        });
      }
      const exists = pins.some((p) => p.id === pin.pinId);
      if (exists) {
        setSelectedPinId(pin.pinId);
      }
    },
    [map, pins],
  );

  // 룰렛: "지도에서 보기" — flyTo + popup 자동.
  const handleShowOnMap = useCallback(
    (pin: PinSummaryResponse) => {
      if (map) {
        map.flyTo({
          center: [Number(pin.longitude), Number(pin.latitude)],
          zoom: 14,
        });
      }
      setSelectedPinId(pin.id);
      setActiveSheet(null);
      setRouletteState({ status: "idle" });
      if (spinTimerRef.current !== null) {
        window.clearTimeout(spinTimerRef.current);
        spinTimerRef.current = null;
      }
    },
    [map],
  );

  // 룰렛: "다시" — 같은 풀에서 재추첨 또는 토글이 바뀌었으면 풀 재구성 (FR-REC-6).
  const handleReRoll = useCallback(() => {
    if (rouletteState.status !== "picked") return;
    const { candidates, radiusKm, center, includeMemoryAtPick, pin: prevPin } =
      rouletteState;

    if (includeMemory !== includeMemoryAtPick) {
      // 토글 상태가 변했으므로 풀을 재구성하여 새로 추첨.
      setRouletteState({
        status: "spinning",
        radiusKm: 10,
        candidateCount: 0,
      });
      if (spinTimerRef.current !== null) {
        window.clearTimeout(spinTimerRef.current);
      }
      void runRoulette(
        center,
        computeTagsAllowed(includeMemory),
      );
      return;
    }

    // 동일 풀에서 재추첨.
    setRouletteState({
      status: "spinning",
      radiusKm,
      candidateCount: candidates.length,
    });
    if (spinTimerRef.current !== null) {
      window.clearTimeout(spinTimerRef.current);
    }
    spinTimerRef.current = window.setTimeout(() => {
      spinTimerRef.current = null;
      const next = reRollFromSamePool(center, candidates, radiusKm, prevPin.id);
      if (next.kind === "exhausted") {
        setRouletteState({ status: "exhausted" });
        return;
      }
      setRouletteState({
        status: "picked",
        pin: next.pin,
        distanceKm: next.distanceKm,
        radiusKm: next.radiusKm,
        candidates: next.candidates,
        center,
        includeMemoryAtPick,
      });
    }, SPIN_DURATION_MS);
  }, [rouletteState, includeMemory, runRoulette]);

  // 태그 변경 즉시 popup 도 갱신되도록 optimisticPins 에서 찾는다 (MUST-5).
  const selectedPin =
    selectedPinId !== null
      ? (optimisticPins.find((p) => p.id === selectedPinId) ?? null)
      : null;

  // 패널 컨테이너 + 콘텐츠 분기 (CONSIDER-2: 컨테이너만 viewport로 갈라짐)
  const renderPanel = (title: string, content: React.ReactNode, opts?: { halfHeight?: boolean }) => {
    if (isDesktop) {
      return (
        <SidePanel
          title={
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
              }}
            >
              <span>{title}</span>
              <button
                type="button"
                onClick={handleSheetClose}
                aria-label="닫기"
                style={{
                  background: "transparent",
                  border: "none",
                  cursor: "pointer",
                  color: colors.inkSoft,
                  fontSize: 18,
                  padding: 0,
                  lineHeight: 1,
                }}
              >
                ×
              </button>
            </div>
          }
          style={{ left: 66 }}
        >
          {content}
        </SidePanel>
      );
    }
    // 모바일 시트: 키보드 등장 시 ActionBar 가 unmount 되므로 bottomOffset 을 12 로 낮춰
    // ActionBar 자리까지 시트가 확장되도록 하고, maxHeight 로 내부 스크롤을 활성화한다.
    const bottomOffset = keyboardOpen ? 12 : 88;
    // 사용 가능한 높이 = 컨테이너(키보드 차감 후) - bottomOffset - 상단 여유(16px).
    // landscape 키보드 케이스에서 음수가 되지 않도록 최소 200px 보장.
    const viewportHeight =
      (typeof window !== "undefined" && window.visualViewport
        ? window.visualViewport.height
        : typeof window !== "undefined"
          ? window.innerHeight
          : 800) - keyboardHeight;
    const maxHeight = opts?.halfHeight
      ? Math.max(200, viewportHeight * 0.5)
      : Math.max(200, viewportHeight - bottomOffset - 16);
    return (
      <Sheet bottomOffset={bottomOffset} maxHeight={maxHeight}>
        {content}
      </Sheet>
    );
  };

  // 룰렛 시트 콘텐츠 렌더.
  const renderRouletteContent = (): React.ReactNode => {
    if (rouletteState.status === "spinning") {
      return (
        <RouletteSpinContent
          radiusKm={rouletteState.radiusKm}
          candidateCount={rouletteState.candidateCount}
        />
      );
    }
    if (rouletteState.status === "picked") {
      return (
        <RouletteResultContent
          pin={rouletteState.pin}
          distanceKm={rouletteState.distanceKm}
          onShowOnMap={() => handleShowOnMap(rouletteState.pin)}
          onReRoll={handleReRoll}
        />
      );
    }
    if (rouletteState.status === "exhausted") {
      return (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            padding: "12px 0 6px",
            gap: 8,
          }}
        >
          <IconLocation size={28} color={colors.inkFaint} />
          <div
            style={{
              fontFamily: fonts.serif,
              fontSize: 16,
              fontWeight: 700,
              color: colors.ink,
            }}
          >
            현재 조건에 맞는 핀이 없어요
          </div>
          <div
            style={{
              fontFamily: fonts.sans,
              fontSize: 13,
              color: colors.inkSoft,
              textAlign: "center",
              lineHeight: 1.5,
            }}
          >
            10km 이내에 추첨 후보가 없어요.
            {!includeMemory && " 추억 핀도 포함해 다시 시도해 보세요."}
          </div>
          <button
            type="button"
            onClick={handleRouletteTap}
            style={{
              marginTop: 8,
              padding: "8px 18px",
              borderRadius: 999,
              border: "none",
              background: colors.cta,
              color: "#ffffff",
              fontFamily: fonts.sans,
              fontSize: 13,
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            다시 시도
          </button>
        </div>
      );
    }
    if (rouletteState.status === "geo-error") {
      return (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            padding: "12px 0 6px",
            gap: 8,
          }}
        >
          <IconLocation size={28} color={colors.inkFaint} />
          <div
            style={{
              fontFamily: fonts.serif,
              fontSize: 16,
              fontWeight: 700,
              color: colors.ink,
            }}
          >
            위치를 확인할 수 없어요
          </div>
          <div
            style={{
              fontFamily: fonts.sans,
              fontSize: 13,
              color: colors.inkSoft,
              textAlign: "center",
              lineHeight: 1.5,
            }}
          >
            {rouletteState.message}
          </div>
          <button
            type="button"
            onClick={handleRouletteTap}
            style={{
              marginTop: 8,
              padding: "8px 18px",
              borderRadius: 999,
              border: "none",
              background: colors.cta,
              color: "#ffffff",
              fontFamily: fonts.sans,
              fontSize: 13,
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            다시 시도
          </button>
        </div>
      );
    }
    // idle: 다이얼로그가 표시 중이거나 권한 응답을 기다리는 짧은 순간.
    return (
      <div
        style={{
          fontFamily: fonts.sans,
          fontSize: 13,
          color: colors.inkSoft,
          textAlign: "center",
          padding: "12px 0",
        }}
      >
        위치 권한을 확인하고 있어요...
      </div>
    );
  };

  let activePanel: React.ReactNode = null;
  if (activeSheet === "search") {
    activePanel = renderPanel(
      "장소 검색",
      <SearchPanelContent onSelectPlace={handleSelectPlace} />,
    );
  } else if (activeSheet === "add") {
    activePanel = renderPanel(
      "위치 선택",
      <AddPinPickerContent
        map={map}
        mapboxToken={mapboxToken}
        onCancel={handleSheetClose}
        onConfirm={handleConfirmCrosshair}
      />,
    );
  } else if (activeSheet === "memo" && addPinOrigin) {
    activePanel = renderPanel(
      "새 핀 추가",
      <MemoTagPanelContent
        origin={addPinOrigin}
        groupId={groupId}
        mapboxToken={mapboxToken}
        onCancel={handleCancelMemo}
        onSuccess={handlePinCreated}
      />,
      { halfHeight: true },
    );
  } else if (activeSheet === "roulette") {
    activePanel = renderPanel("오늘 어디 갈까?", renderRouletteContent());
  } else if (activeSheet === "coordinate-edit" && coordinateEditTarget) {
    activePanel = renderPanel(
      "좌표 수정",
      <PinCoordinateEditPicker
        map={map}
        mapboxToken={mapboxToken}
        initialPin={coordinateEditTarget}
        onCancel={handleCancelCoordinateEdit}
        onConfirm={handleConfirmCoordinateEdit}
      />,
    );
  } else if (
    activeSheet === "visit-memo" &&
    visitMemoPin &&
    visitedAtRef.current
  ) {
    activePanel = renderPanel(
      "방문 기록",
      <VisitMemoSheet
        pin={visitMemoPin}
        visitedAt={visitedAtRef.current}
        onSave={handleVisitMemoSave}
        onSkip={handleVisitMemoSkip}
      />,
      { halfHeight: true },
    );
  }

  // Phase 8: 알림 벨 — 모바일은 하단 ActionBar 4번째 탭, 데스크탑은 사이드바 하단.
  // 클릭 시 패널 토글. 열려 있으면 닫고, 닫혀 있으면 활성 시트를 닫고 패널을 연다(동시 1개 패널 정책).
  const handleBellClick = () => {
    if (notifications.isPanelOpen) {
      notifications.closePanel();
      return;
    }
    setActiveSheet(null);
    // Phase 10: 동시 1개 패널 정책 — 방문 토스트/메모 시트도 함께 닫는다.
    setVisitToastPin(null);
    setVisitMemoPin(null);
    void notifications.openPanel();
  };

  const desktopBell = (
    <NotificationBell
      variant="desktop"
      unreadCount={notifications.unreadCount}
      onClick={handleBellClick}
    />
  );

  return (
    <div
      style={{
        position: "absolute",
        top: 0,
        left: 0,
        right: 0,
        // 모바일 키보드 인셋만큼 컨테이너 하단을 줄여, 내부 absolute bottom 기준이
        // visualViewport 하단과 일치하도록 한다. mapbox v3 ResizeObserver 가
        // 컨테이너 크기 변화를 자동 감지하여 캔버스를 재조정한다.
        bottom: keyboardHeight,
        background: colors.bg,
        overflow: "hidden",
        transition: "bottom 150ms ease",
      }}
    >
      <MapboxView
        ref={mapboxViewRef}
        pins={optimisticPins}
        token={mapboxToken}
        styleUrl={mapboxStyleUrl}
        onMarkerClick={handleMarkerClick}
        onMapBackgroundClick={() => setSelectedPinId(null)}
        onMapReady={handleMapReady}
        onClustersChange={handleClustersChange}
        onMapError={setMapError}
        onGeolocate={handleGeolocate}
        previewMarker={
          activeSheet === "memo" && addPinOrigin
            ? {
                lat: Number(addPinOrigin.latitude),
                lng: Number(addPinOrigin.longitude),
              }
            : null
        }
        skipInitialGeoFly={skipInitialGeoFly}
      />
      {mapError && <MapLoadError reason={mapError} />}
      <MobileTopNav
        myNickname={myNickname}
        showProfile={!isDesktop}
      />
      <ClusterBanner visible={hasCluster} />
      <div
        style={{
          position: "absolute",
          bottom: isDesktop ? 24 : 92,
          left: 14,
          zIndex: 20,
        }}
      >
        <TagLegendButton />
      </div>
      {pins.length === 0 && !activeSheet && (
        <EmptyMapCard
          isDesktop={isDesktop}
          onAddPin={() => handleTabChange("add")}
        />
      )}
      {(activeSheet === "add" || activeSheet === "coordinate-edit") && (
        <CrosshairOverlay />
      )}
      {selectedPin && (
        <PinPopup
          pin={selectedPin}
          map={map}
          authorLabel={
            // 상대방이 메모를 수정한 경우 written by 이름을 수정자로 변경
            (selectedPin.memoUpdatedBy != null &&
              selectedPin.memoUpdatedBy !== selectedPin.createdBy)
              ? (selectedPin.memoUpdatedByNickname ?? `사용자 #${selectedPin.memoUpdatedBy}`)
              : (selectedPin.createdByNickname ?? `사용자 #${selectedPin.createdBy}`)
          }
          mapboxToken={mapboxToken}
          mapboxStyleUrl={mapboxStyleUrl}
          groupPins={optimisticPins}
          onTagChange={handleTagChange}
          onMemoChange={handleMemoChange}
          onPlaceNameChange={handlePlaceNameChange}
          onRequestDelete={(pin) => {
            setDeleteErrorByPinId((prev) => {
              if (!(pin.id in prev)) return prev;
              const next = { ...prev };
              delete next[pin.id];
              return next;
            });
            setDeleteCandidate(pin);
          }}
          deleteError={deleteErrorByPinId[selectedPin.id] ?? null}
          onRequestCoordinateEdit={handleRequestCoordinateEdit}
          coordinateError={coordinateErrorByPinId[selectedPin.id] ?? null}
        />
      )}
      {deleteCandidate && (
        <PinDeleteConfirm
          pin={deleteCandidate}
          onCancel={() => setDeleteCandidate(null)}
          onConfirm={handleConfirmDelete}
        />
      )}
      {activePanel}
      {visitToastPin && (
        <VisitToast
          pin={visitToastPin}
          onSkip={handleVisitSkip}
          onConfirm={handleVisitConfirm}
        />
      )}
      {visitErrorMessage && (
        <div
          role="alert"
          style={{
            position: "fixed",
            bottom: 100,
            left: 12,
            right: 12,
            zIndex: 30,
            background: colors.ink,
            color: colors.bg,
            padding: "12px 16px",
            borderRadius: 12,
            fontFamily: fonts.sans,
            fontSize: 13,
            textAlign: "center",
            boxShadow: "0 4px 16px rgba(0,0,0,0.2)",
          }}
        >
          {visitErrorMessage}
        </div>
      )}
      {visitInfoMessage && !visitErrorMessage && (
        <div
          role="status"
          style={{
            // AC-VD-25: 동시 수정으로 짝꿍이 먼저 메모리로 전환한 핀 안내 (중립 톤).
            position: "fixed",
            bottom: 100,
            left: 12,
            right: 12,
            zIndex: 30,
            background: colors.ink,
            color: colors.bg,
            padding: "12px 16px",
            borderRadius: 12,
            fontFamily: fonts.sans,
            fontSize: 13,
            textAlign: "center",
            boxShadow: "0 4px 16px rgba(0,0,0,0.2)",
          }}
        >
          {visitInfoMessage}
        </div>
      )}
      {isDesktop ? (
        <DesktopActionPill
          active={activeSheetToTab(activeSheet)}
          onChange={handleTabChange}
          rouletteDisabled={
            rouletteDeferred ||
            permissionState === "denied" ||
            geoState.status === "denied"
          }
          myNickname={myNickname}
          notificationBell={desktopBell}
        />
      ) : keyboardOpenForLayout ? null : (
        // 모바일 키보드 등장 시 ActionBar 를 unmount 하여 입력 공간을 확보한다.
        // 닫힘은 keyboardOpenForLayout 으로 150ms 지연하여 Sheet/컨테이너 transition 후 mount.
        // ActionBar 자체에 kbd-fadein 100ms animation 이 있어 자연스럽게 등장한다.
        <ActionBar
          active={activeSheetToTab(activeSheet)}
          onChange={handleTabChange}
          rouletteDisabled={
            rouletteDeferred ||
            permissionState === "denied" ||
            geoState.status === "denied"
          }
          notificationActive={notifications.isPanelOpen}
          notificationUnreadCount={notifications.unreadCount}
          onNotificationClick={handleBellClick}
        />
      )}
      {notifications.toast && (
        <NotificationToast
          key={notifications.toast.id}
          payload={notifications.toast.payload}
          onDismiss={notifications.dismissToast}
        />
      )}
      <NotificationPanel
        items={notifications.items}
        isOpen={notifications.isPanelOpen}
        onClose={notifications.closePanel}
        onSelectPin={handleSelectPinFromNotification}
        loadDetail={notifications.loadDetail}
        variant={isDesktop ? "desktop" : "mobile"}
        currentUserId={myId}
      />
      {showPermDialog && (
        <PermissionDialog
          title="위치를 알려주세요"
          description={
            "근처에 어떤 핀이 있는지\n랜덤 뽑기에서 활용할 거예요"
          }
          primaryLabel="위치 사용 허용"
          secondaryLabel="나중에"
          onPrimary={() => {
            setShowPermDialog(false);
            setRouletteDeferred(false);
            pendingRouletteRef.current = true;
            setRouletteState({
              status: "spinning",
              radiusKm: 10,
              candidateCount: 0,
            });
            geoRequest();
          }}
          onSecondary={() => {
            setShowPermDialog(false);
            // 셔플 비활성화 + 룰렛 시트 닫기.
            // 사용자가 명시적으로 "나중에" 를 선택했으므로 ActionBar/Sidebar 셔플 탭을 비활성화한다.
            setRouletteDeferred(true);
            setActiveSheet(null);
            setRouletteState({ status: "idle" });
            if (spinTimerRef.current !== null) {
              window.clearTimeout(spinTimerRef.current);
              spinTimerRef.current = null;
            }
          }}
          layout="vertical"
          onMap
        />
      )}
    </div>
  );
}

/**
 * 내부 시트 상태(memo 포함)를 액션바 탭(search/add/roulette/null)으로 매핑.
 * memo 단계는 add 흐름의 연장이므로 add 탭이 활성으로 표시된다.
 */
function activeSheetToTab(sheet: ActiveSheet): ActionBarTab {
  if (sheet === "search") return "search";
  if (sheet === "add" || sheet === "memo") return "add";
  if (sheet === "roulette") return "roulette";
  // "coordinate-edit"는 액션바 비강조 (Phase 2.10 B4b): null fallback.
  return null;
}

/**
 * 룰렛 풀 필터링을 위한 `tagsAllowed` 배열 산출 헬퍼 (Phase 7 D4 정합화).
 *
 * Phase 2.6 PR-A 도입 시 `runRoulette(tagsAllowed)` 시그니처는 MEMORY 토글을
 * 받을 수 있게 설계됐지만 호출처가 `["PLACE", "MEMORY"]` 하드코딩으로 토글을
 * 무시하던 부분 버그가 있었다. Phase 7 에서 PinTag 가 REEL/WISH/MEMORY 로
 * 리뉴얼되면서 이 헬퍼로 호출처를 일관화한다.
 *
 * - includeMemory=true  → ["REEL", "WISH", "MEMORY"]
 * - includeMemory=false → ["REEL", "WISH"]
 */
function computeTagsAllowed(includeMemory: boolean): PinTag[] {
  return includeMemory ? ["REEL", "WISH", "MEMORY"] : ["REEL", "WISH"];
}
