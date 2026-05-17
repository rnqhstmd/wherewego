"use client";

import dynamic from "next/dynamic";
import {
  useCallback,
  useEffect,
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
import ActionBar from "./_components/ActionBar";
import DesktopSidebar from "./_components/DesktopSidebar";
import SearchPanelContent from "./_components/SearchPanelContent";
import AddPinPickerContent from "./_components/AddPinPickerContent";
import CrosshairOverlay from "./_components/CrosshairOverlay";
import MemoTagPanelContent from "./_components/MemoTagPanelContent";
import RouletteSpinContent from "./_components/RouletteSpinContent";
import RouletteResultContent from "./_components/RouletteResultContent";
import ClusterBanner from "./_components/ClusterBanner";
import EmptyMapCard from "./_components/EmptyMapCard";
import MapLoadError, {
  type MapLoadErrorReason,
} from "./_components/MapLoadError";
import { useMediaQuery } from "./_hooks/useMediaQuery";
import { useGeolocation, type LatLng } from "./_hooks/useGeolocation";
import {
  pickRandomWithExpansion,
  reRollFromSamePool,
  type RouletteRadiusKm,
} from "./_lib/roulette";
import { updatePinTagAction } from "./actions";
import type { ActionBarTab, NewPinOrigin } from "./_components/types";

/**
 * MapboxView는 mapbox-gl이 window 의존이므로 ssr:false로 동적 로드.
 * Server Component에서는 ssr:false 옵션이 허용되지 않으므로 반드시 이 Client Component에서 호출.
 */
const MapboxView = dynamic(() => import("./_components/MapboxView"), {
  ssr: false,
  loading: () => (
    <div style={{ position: "absolute", inset: 0, background: colors.bg }} />
  ),
});

interface MapClientProps {
  initialPins: PinSummaryResponse[];
  groupId: number;
  groupName: string;
  mapboxToken: string;
  mapboxStyleUrl: string | null;
}

type ActiveSheet = "search" | "add" | "memo" | "roulette" | null;

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
}: MapClientProps) {
  const [pins, setPins] = useState<PinSummaryResponse[]>(initialPins);
  // MUST-5: 태그 토글의 마커 모양 즉시 갱신용. pins 가 바뀌면 자동 동기화.
  const [optimisticPins, applyOptimistic] = useOptimistic<
    PinSummaryResponse[],
    { pinId: number; tag: PinTag }
  >(pins, (current, action) =>
    current.map((p) =>
      p.id === action.pinId ? { ...p, tag: action.tag } : p,
    ),
  );
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [_isOptimisticPending, startOptimisticTransition] = useTransition();
  const [selectedPinId, setSelectedPinId] = useState<number | null>(null);
  const [map, setMap] = useState<mapboxgl.Map | null>(null);
  const [hasCluster, setHasCluster] = useState(false);
  const [mapError, setMapError] = useState<MapLoadErrorReason | null>(null);

  const [activeSheet, setActiveSheet] = useState<ActiveSheet>(null);
  const [addPinOrigin, setAddPinOrigin] = useState<NewPinOrigin | null>(null);

  // 룰렛 관련 상태.
  const { state: geoState, request: geoRequest } = useGeolocation();
  const [showPermDialog, setShowPermDialog] = useState(false);
  const [rouletteState, setRouletteState] = useState<RouletteUIState>({
    status: "idle",
  });
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

  const isDesktop = useMediaQuery("(min-width: 768px)");

  const handleMarkerClick = useCallback((pinId: number) => {
    setSelectedPinId(pinId);
  }, []);

  const handleMapReady = useCallback((next: mapboxgl.Map) => {
    setMap(next);
  }, []);

  const handleClustersChange = useCallback(
    ({ hasCluster: hc }: { hasCluster: boolean }) => {
      setHasCluster(hc);
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
    async (
      pinId: number,
      nextTag: PinTag,
    ): Promise<{ ok: boolean; message?: string }> => {
      startOptimisticTransition(() => {
        applyOptimistic({ pinId, tag: nextTag });
      });
      const result = await updatePinTagAction(groupId, pinId, nextTag);
      if (result.ok) {
        setPins((prev) =>
          prev.map((p) => (p.id === pinId ? result.data : p)),
        );
        return { ok: true };
      }
      const message =
        result.code === "GROUP_NOT_MEMBER"
          ? "권한이 없어요"
          : result.message;
      return { ok: false, message };
    },
    [applyOptimistic, groupId],
  );

  /**
   * 좌표 + 핀 목록을 받아 추첨을 수행하고 결과를 상태에 반영.
   * 추첨 직전 5분 캐시 정책으로 stale이면 await 재조회 (MUST-4).
   */
  const runRoulette = useCallback(
    async (center: LatLng) => {
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

      const result = pickRandomWithExpansion(center, pool, ["PLACE"]);
      if (result.kind === "exhausted") {
        setRouletteState({ status: "exhausted" });
        return;
      }
      // 짧은 spin 연출 후 picked 전이 (M-6 → M-6c).
      setRouletteState({
        status: "spinning",
        radiusKm: result.radiusKm,
        candidateCount: result.candidateCount,
      });
      window.setTimeout(() => {
        setRouletteState({
          status: "picked",
          pin: result.pin,
          distanceKm: result.distanceKm,
          radiusKm: result.radiusKm,
          candidates: result.candidates,
          center,
        });
      }, SPIN_DURATION_MS);
    },
    [groupId, pins],
  );

  /**
   * 셔플 탭 진입점. 권한 상태에 따라 다이얼로그/요청/즉시추첨 분기.
   */
  const handleRouletteTap = useCallback(() => {
    setSelectedPinId(null);
    setActiveSheet("roulette");

    if (geoState.status === "denied") {
      // 명시적 거부 상태: 다이얼로그 재안내.
      setShowPermDialog(true);
      return;
    }
    if (geoState.status === "granted") {
      setRouletteState({
        status: "spinning",
        radiusKm: 1,
        candidateCount: 0,
      });
      void runRoulette(geoState.coords);
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
        radiusKm: 1,
        candidateCount: 0,
      });
      return;
    }
    // idle 또는 prompting: 사전 다이얼로그 안내 후 요청 (디자인의 권한 안내).
    setShowPermDialog(true);
  }, [geoState, geoRequest, runRoulette]);

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
        void runRoulette(geoState.coords);
      } else if (status === "denied") {
        setShowPermDialog(true);
        setRouletteState({ status: "idle" });
      } else if (status === "unavailable") {
        setRouletteState({
          status: "geo-error",
          message: "이 브라우저에서는 위치를 사용할 수 없어요.",
        });
      } else if (status === "timeout") {
        setRouletteState({
          status: "geo-error",
          message: "위치 확인이 어려워요. 잠시 후 다시 시도해 주세요.",
        });
      }
    }, 0);
    return () => window.clearTimeout(handle);
  }, [geoState, runRoulette]);

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
      }
    },
    [activeSheet, handleRouletteTap],
  );

  // 검색에서 장소 선택 → MemoTag 단계로 전이
  const handleSelectPlace = useCallback((place: PlaceSearchItem) => {
    setAddPinOrigin({
      placeName: place.placeName,
      address: place.address,
      latitude: place.latitude,
      longitude: place.longitude,
      editable: false,
    });
    setActiveSheet("memo");
  }, []);

  // Crosshair에서 좌표 확정 → MemoTag 단계로 전이 (placeName은 사용자 입력)
  const handleConfirmCrosshair = useCallback(
    (coords: { lng: number; lat: number }) => {
      setAddPinOrigin({
        placeName: "",
        address: null,
        latitude: coords.lat,
        longitude: coords.lng,
        editable: true,
      });
      setActiveSheet("memo");
    },
    [],
  );

  const handleCancelMemo = useCallback(() => {
    setActiveSheet(null);
    setAddPinOrigin(null);
  }, []);

  // 저장 성공 → 클라 state 에 직접 추가 (revalidate 없음, MUST-1)
  const handlePinCreated = useCallback((newPin: PinSummaryResponse) => {
    setPins((prev) => [...prev, newPin]);
    // 캐시 fetchedAt도 갱신: 방금 만든 핀까지 포함하는 fresh 상태.
    if (pinsCacheRef.current) {
      pinsCacheRef.current.fetchedAt = Date.now();
    }
    setActiveSheet(null);
    setAddPinOrigin(null);
    setSelectedPinId(newPin.id);
  }, []);

  const handleSheetClose = useCallback(() => {
    setActiveSheet(null);
    setAddPinOrigin(null);
    setRouletteState({ status: "idle" });
    pendingRouletteRef.current = false;
  }, []);

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
    },
    [map],
  );

  // 룰렛: "다시" — 같은 풀에서 재추첨 (FR-REC-6).
  const handleReRoll = useCallback(() => {
    if (rouletteState.status !== "picked") return;
    const { candidates, radiusKm, center } = rouletteState;
    setRouletteState({
      status: "spinning",
      radiusKm,
      candidateCount: candidates.length,
    });
    window.setTimeout(() => {
      const next = reRollFromSamePool(center, candidates, radiusKm);
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
      });
    }, SPIN_DURATION_MS);
  }, [rouletteState]);

  // 태그 변경 즉시 popup 도 갱신되도록 optimisticPins 에서 찾는다 (MUST-5).
  const selectedPin =
    selectedPinId !== null
      ? (optimisticPins.find((p) => p.id === selectedPinId) ?? null)
      : null;

  // 패널 컨테이너 + 콘텐츠 분기 (CONSIDER-2: 컨테이너만 viewport로 갈라짐)
  const renderPanel = (title: string, content: React.ReactNode) => {
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
          style={{ left: 52 }}
        >
          {content}
        </SidePanel>
      );
    }
    return <Sheet>{content}</Sheet>;
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
            이 지도에 아직 핀이 없어요
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
            10km 이내에서 추첨할 만한 장소를
            <br />
            먼저 추가해 보세요.
          </div>
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
        onCancel={handleCancelMemo}
        onSuccess={handlePinCreated}
      />,
    );
  } else if (activeSheet === "roulette") {
    activePanel = renderPanel("오늘 어디 갈까?", renderRouletteContent());
  }

  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        background: colors.bg,
        overflow: "hidden",
      }}
    >
      <MapboxView
        pins={optimisticPins}
        token={mapboxToken}
        styleUrl={mapboxStyleUrl}
        onMarkerClick={handleMarkerClick}
        onMapReady={handleMapReady}
        onClustersChange={handleClustersChange}
        onMapError={setMapError}
      />
      {mapError && <MapLoadError reason={mapError} />}
      <ClusterBanner visible={hasCluster} />
      {pins.length === 0 && !activeSheet && (
        <EmptyMapCard
          isDesktop={isDesktop}
          onAddPin={() => handleTabChange("add")}
        />
      )}
      {activeSheet === "add" && <CrosshairOverlay />}
      {selectedPin && (
        <PinPopup
          pin={selectedPin}
          map={map}
          onTagChange={handleTagChange}
        />
      )}
      {activePanel}
      {isDesktop ? (
        <DesktopSidebar
          active={activeSheetToTab(activeSheet)}
          onChange={handleTabChange}
        />
      ) : (
        <ActionBar
          active={activeSheetToTab(activeSheet)}
          onChange={handleTabChange}
        />
      )}
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
            pendingRouletteRef.current = true;
            setRouletteState({
              status: "spinning",
              radiusKm: 1,
              candidateCount: 0,
            });
            geoRequest();
          }}
          onSecondary={() => {
            setShowPermDialog(false);
            // 룰렛 시트도 닫음 (셔플 비활성 안내 대신 시트 닫기).
            setActiveSheet(null);
            setRouletteState({ status: "idle" });
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
  return null;
}
