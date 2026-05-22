// Phase 9: 핀 공유 카드(1080×1350 PNG) Canvas 렌더러.
// 설계 §"상세 설계" 8번의 9 Step 알고리즘을 충실히 구현한다.
//
// 외부 export:
//   - renderPinCard(input, signal?) : 메인 비동기 렌더 함수
//   - wrapAndEllipsize             : 단위 테스트 가능한 줄바꿈/말줄임 알고리즘
//   - isCanvasSupported            : Canvas 지원 가드 (SSR safe)

import type { PinSummaryResponse } from "@/lib/api/types";
import { extractStyleId } from "@/lib/share/mapboxStaticUrl";
import { geoToApiPixel } from "@/lib/share/geoToPixel";
import {
  getReelSvgString,
  getWishSvgString,
  getMemorySvgString,
} from "@/lib/pin/markers";

// 카드 픽셀 사이즈(4:5)
const CARD_WIDTH = 1080;
const CARD_HEIGHT = 1350;

// 콘텐츠 패딩
const PADDING_X = 64;
const CONTENT_MAX_WIDTH = CARD_WIDTH - PADDING_X * 2; // 952

// Mapbox Static API 사이즈 (1280 한도 내)
const MAPBOX_API_WIDTH = 1024;
const MAPBOX_API_HEIGHT = 1280;
const MAPBOX_ZOOM = 14;
const MAPBOX_TIMEOUT_MS = 8000;

// BR-6 폴백 단색 (warm sand)
const FALLBACK_BACKGROUND_COLOR = "#EAE4D4";

// 콘텐츠 시작 y좌표 — 메모가 카드 세로 중앙에 가깝게 위치하도록 540 설정.
// (CARD_HEIGHT 1350 기준 ~40% 지점, 워터마크 영역 제외 시 시각 중앙)
const CONTENT_START_Y = 540;

// 색상 토큰
const COLOR_MEMO = "rgba(26, 26, 46, 0.95)";
const COLOR_PLACE_NAME = "#1A1A2E";
const COLOR_META = "rgba(26, 26, 46, 0.6)";
const COLOR_WATERMARK = "rgba(26, 26, 46, 0.55)";

// 폰트 사이즈
const FONT_MEMO_PX = 60;
const FONT_PLACE_PX = 36;
const FONT_META_PX = 22;
const FONT_WATERMARK_PX = 24;

// 줄 높이
const LINE_HEIGHT_MEMO = 84; // 60 * 1.4 = 84
const LINE_HEIGHT_PLACE = 43; // 36 * 1.2 ≈ 43.2
const LINE_HEIGHT_META = 28;

// 요소 간 gap
const GAP_AFTER_MEMO = 32;
const GAP_AFTER_PLACE = 12;
const GAP_AFTER_DATE = 8;

// 워터마크 위치
const WATERMARK_BOTTOM_OFFSET = 64;

// Step 4.5: 커스텀 핀 글리프 사이즈 상수
const GLYPH_SIZE_OTHER = 24; // 그룹 핀 아이콘 px
const GLYPH_SIZE_SELF  = 40; // 자기 핀 아이콘 px
const BG_RADIUS_OTHER  = 16; // 그룹 핀 흰 원 배경 반지름 px
const BG_RADIUS_SELF   = 24; // 자기 핀 흰 원 배경 반지름 px
// API 응답(1024×1280) → 카드 캔버스(1080×1350) 좌표 변환 비율
const SCALE_X = CARD_WIDTH  / MAPBOX_API_WIDTH;
const SCALE_Y = CARD_HEIGHT / MAPBOX_API_HEIGHT;

/** 카드 배경 지도에 함께 표시할 다른 핀 마커 정보 (자기 핀 제외). 자기 핀은 중앙에 자동 표시. */
export interface CardGroupPinMarker {
  id: number;
  latitude: number;
  longitude: number;
  tag: "REEL" | "WISH" | "MEMORY";
}

export interface RenderPinCardInput {
  pin: PinSummaryResponse;
  /** BR-1: 호출처가 미리 처리한 표시명 ("익명" 포함) */
  authorLabel: string;
  /** BR-10: 호출처가 미리 처리한 YYYY.MM.DD */
  formattedDate: string;
  mapboxToken: string;
  mapboxStyleUrl: string | null;
  /** 카드 배경 지도에 함께 표시할 그룹 내 다른 핀들. 자기 핀은 자동 제외됨. */
  groupPins?: CardGroupPinMarker[];
}

export interface RenderPinCardResult {
  blob: Blob;
  previewDataUrl: string;
}

type PinTag = "REEL" | "WISH" | "MEMORY";

/** Mapbox Static 서버사이드 프록시 URL 생성. CORS·토큰 노출 방지. */
function buildProxyUrl(
  lat: number,
  lng: number,
  styleId: string,
): string {
  return (
    `/api/mapbox-static` +
    `?lat=${lat.toFixed(6)}&lng=${lng.toFixed(6)}` +
    `&zoom=${MAPBOX_ZOOM}&w=${MAPBOX_API_WIDTH}&h=${MAPBOX_API_HEIGHT}` +
    `&styleId=${encodeURIComponent(styleId)}`
  );
}

function loadSvgImage(svgStr: string): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const el = new Image();
    el.onload = () => resolve(el);
    el.onerror = () => resolve(null);
    el.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svgStr)}`;
  });
}

/** SSR safe — top-level에서 document 접근하지 않는다. */
export function isCanvasSupported(): boolean {
  if (typeof document === "undefined") {
    return false;
  }
  const canvas = document.createElement("canvas");
  if (typeof canvas.getContext !== "function") {
    return false;
  }
  return !!canvas.getContext("2d");
}

function throwIfAborted(signal: AbortSignal | undefined): void {
  if (signal && signal.aborted) {
    throw new DOMException("Aborted", "AbortError");
  }
}

/**
 * Mapbox Static 이미지 로드. 8초 timeout, AbortSignal, onerror 모두 null로 떨어진다.
 * BR-6: 실패해도 throw하지 않고 호출처가 단색 폴백을 그리도록 한다.
 */
function loadImageWithTimeout(
  url: string,
  timeoutMs: number,
  signal: AbortSignal | undefined,
  pinId: number,
): Promise<HTMLImageElement | null> {
  return new Promise<HTMLImageElement | null>((resolve) => {
    const img = new Image();

    let settled = false;
    const settle = (result: HTMLImageElement | null, reason: string | null) => {
      if (settled) return;
      settled = true;
      if (timeoutId !== null) {
        clearTimeout(timeoutId);
      }
      if (signal) {
        signal.removeEventListener("abort", onAbort);
      }
      img.onload = null;
      img.onerror = null;
      if (result === null && reason !== null) {
        // 운영 모니터링용
        console.warn("Mapbox Static failed for pin", pinId, reason);
      }
      resolve(result);
    };

    const onAbort = () => settle(null, "aborted");

    img.onload = () => settle(img, null);
    img.onerror = () => settle(null, "error");

    const timeoutId: ReturnType<typeof setTimeout> = setTimeout(() => {
      settle(null, "timeout");
    }, timeoutMs);

    if (signal) {
      if (signal.aborted) {
        settle(null, "aborted");
        return;
      }
      signal.addEventListener("abort", onAbort);
    }

    img.src = url;
  });
}

/**
 * 단어 단위 greedy 줄바꿈 + 마지막 줄 말줄임("…").
 * 단어 하나가 maxWidth보다 길면 문자 단위로 강제 분할.
 *
 * @param text          원본 텍스트 (공백 split)
 * @param maxWidth      한 줄 최대 너비(px)
 * @param maxLines      최대 줄 수 (0이면 빈 배열)
 * @param measureTextFn 너비 측정 함수 (Canvas.measureText 래핑)
 */
export function wrapAndEllipsize(
  text: string,
  maxWidth: number,
  maxLines: number,
  measureTextFn: (s: string) => number,
): string[] {
  if (maxLines <= 0) {
    return [];
  }
  if (!text) {
    return [];
  }

  // 단어 단위 + 너무 긴 단어는 char 단위로 강제 분할
  const rawTokens = text.split(/\s+/).filter((t) => t.length > 0);
  const tokens: string[] = [];
  for (const tok of rawTokens) {
    if (measureTextFn(tok) <= maxWidth) {
      tokens.push(tok);
      continue;
    }
    // 한 단어가 너무 김 → char 단위 분할
    let buf = "";
    for (const ch of tok) {
      const next = buf + ch;
      if (measureTextFn(next) > maxWidth && buf.length > 0) {
        tokens.push(buf);
        buf = ch;
      } else {
        buf = next;
      }
    }
    if (buf.length > 0) {
      tokens.push(buf);
    }
  }

  const lines: string[] = [];
  let lineBuf = "";
  let i = 0;

  // 마지막 줄 직전까지 greedy 줄바꿈
  while (i < tokens.length && lines.length < maxLines - 1) {
    const candidate = lineBuf.length === 0 ? tokens[i] : `${lineBuf} ${tokens[i]}`;
    if (measureTextFn(candidate) <= maxWidth) {
      lineBuf = candidate;
      i += 1;
      continue;
    }
    // 한 줄 끝 — 누적된 lineBuf push
    if (lineBuf.length === 0) {
      // 첫 토큰부터 못 들어감 (안전망)
      lineBuf = tokens[i];
      i += 1;
    }
    lines.push(lineBuf);
    lineBuf = "";
  }

  // 마지막 한 줄: 남은 토큰을 greedy로 채우고, 그래도 남으면 말줄임
  if (lines.length === maxLines - 1) {
    // lineBuf에 이전 루프 잔여가 있으면 그것부터 시작
    let lastBuf = lineBuf;
    while (i < tokens.length) {
      const candidate =
        lastBuf.length === 0 ? tokens[i] : `${lastBuf} ${tokens[i]}`;
      if (measureTextFn(candidate) <= maxWidth) {
        lastBuf = candidate;
        i += 1;
      } else {
        break;
      }
    }
    if (i < tokens.length) {
      // 토큰이 남았다 → 말줄임
      const ELLIPSIS = "…";
      // 남은 토큰을 일단 lastBuf에 이어붙인 뒤 char 단위 자르기
      const tail = tokens.slice(i).join(" ");
      let candidate = lastBuf.length === 0 ? tail : `${lastBuf} ${tail}`;
      while (
        candidate.length > 0 &&
        measureTextFn(candidate + ELLIPSIS) > maxWidth
      ) {
        candidate = candidate.slice(0, -1);
      }
      if (candidate.length > 0) {
        lines.push(candidate + ELLIPSIS);
      } else {
        // 극단 케이스: maxWidth가 ELLIPSIS 너비보다 작아 자를 글자가 없음.
        // ellipsis만이라도 push하여 다음 줄에 시각적 표시 유지.
        lines.push(ELLIPSIS);
      }
    } else if (lastBuf.length > 0) {
      lines.push(lastBuf);
    }
  } else if (lineBuf.length > 0) {
    // maxLines-1 도달 전에 토큰이 끝남 (정상 종료)
    lines.push(lineBuf);
  }

  // 안전망: maxLines 초과 차단
  if (lines.length > maxLines) {
    lines.length = maxLines;
  }

  return lines;
}

/** 폰트 사양 문자열 빌더(fallback 처리 포함) */
function fontStr(spec: string, family: string): string {
  return `${spec} ${family}`;
}

/**
 * Phase 9 핀 공유 카드 렌더.
 * BR-6: Mapbox 실패해도 throw 안 함 — 단색 폴백.
 * BR-7: Canvas 미지원 → throw new Error("CANVAS_UNSUPPORTED").
 */
export async function renderPinCard(
  input: RenderPinCardInput,
  signal?: AbortSignal,
): Promise<RenderPinCardResult> {
  // Step 1 — capabilities 체크
  if (!isCanvasSupported()) {
    throw new Error("CANVAS_UNSUPPORTED");
  }

  throwIfAborted(signal);

  // Step 2 — 폰트 보장
  let usePretendardFallback = false;
  try {
    if (typeof document !== "undefined" && document.fonts) {
      await document.fonts.ready;
      try {
        const results = await Promise.all([
          document.fonts.load(`${FONT_MEMO_PX}px Pretendard`),
          document.fonts.load(`bold ${FONT_PLACE_PX}px Pretendard`),
          document.fonts.load(`${FONT_META_PX}px Pretendard`),
          document.fonts.load(`bold ${FONT_WATERMARK_PX}px Pretendard`),
        ]);
        // document.fonts.load는 폰트 미등록 시 reject가 아닌 빈 배열을 resolve한다.
        // 어느 하나라도 빈 배열이면 Pretendard 미보장 → sans-serif 폴백 전환.
        if (results.some((r) => r.length === 0)) {
          usePretendardFallback = true;
        }
      } catch {
        usePretendardFallback = true;
      }
    } else {
      usePretendardFallback = true;
    }
  } catch {
    usePretendardFallback = true;
  }
  const fontFamily = usePretendardFallback ? "sans-serif" : "Pretendard";

  throwIfAborted(signal);

  // Step 3 — Mapbox Static 이미지 로드 (서버사이드 프록시 경유 — CORS 완전 회피)
  // 1차: 앱 커스텀 스타일. GL JS v3 전용 스타일은 Static API에서 렌더 실패할 수 있으므로
  // 실패 시 light-v11로 자동 재시도해 베이지 단색 폴백을 피한다.
  const { latitude: pinLat, longitude: pinLng } = input.pin;
  let img = await loadImageWithTimeout(
    buildProxyUrl(pinLat, pinLng, extractStyleId(input.mapboxStyleUrl)),
    MAPBOX_TIMEOUT_MS,
    signal,
    input.pin.id,
  );
  if (img === null) {
    img = await loadImageWithTimeout(
      buildProxyUrl(pinLat, pinLng, "mapbox/light-v11"),
      MAPBOX_TIMEOUT_MS,
      signal,
      input.pin.id,
    );
  }

  throwIfAborted(signal);

  // Step 4 — Off-canvas A에 원본(또는 폴백) 그리기
  const canvasA = document.createElement("canvas");
  canvasA.width = CARD_WIDTH;
  canvasA.height = CARD_HEIGHT;
  const ctxA = canvasA.getContext("2d");
  if (!ctxA) {
    throw new Error("CANVAS_UNSUPPORTED");
  }
  if (img === null) {
    ctxA.fillStyle = FALLBACK_BACKGROUND_COLOR;
    ctxA.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT);
  } else {
    ctxA.drawImage(img, 0, 0, CARD_WIDTH, CARD_HEIGHT);
    // 참조 제거(MUST-ADDRESS #2)
    img.src = "";
  }

  // Step 4.5 — 커스텀 핀 글리프를 canvasA에 드로잉 (blur 전 적용)
  // API 실패(img === null)이면 단색 폴백 유지 — 핀 드로잉 skip
  if (img !== null) {
    // tag별 SVG Image 캐시 — 그룹 핀용(GLYPH_SIZE_OTHER) + 자기 핀용(GLYPH_SIZE_SELF)
    const [otherImgResults, selfImgResults] = await Promise.all([
      Promise.allSettled([
        loadSvgImage(getReelSvgString(GLYPH_SIZE_OTHER)),
        loadSvgImage(getWishSvgString(GLYPH_SIZE_OTHER)),
        loadSvgImage(getMemorySvgString(GLYPH_SIZE_OTHER, GLYPH_SIZE_OTHER)),
      ]),
      Promise.allSettled([
        loadSvgImage(getReelSvgString(GLYPH_SIZE_SELF)),
        loadSvgImage(getWishSvgString(GLYPH_SIZE_SELF)),
        loadSvgImage(getMemorySvgString(GLYPH_SIZE_SELF, GLYPH_SIZE_SELF)),
      ]),
    ]);

    throwIfAborted(signal);

    const TAGS: PinTag[] = ["REEL", "WISH", "MEMORY"];
    const otherGlyphMap: Partial<Record<PinTag, HTMLImageElement>> = {};
    const selfGlyphMap:  Partial<Record<PinTag, HTMLImageElement>> = {};
    TAGS.forEach((tag, i) => {
      const or = otherImgResults[i];
      if (or.status === "fulfilled" && or.value) otherGlyphMap[tag] = or.value;
      const sr = selfImgResults[i];
      if (sr.status === "fulfilled" && sr.value) selfGlyphMap[tag] = sr.value;
    });

    const centerLat = input.pin.latitude;
    const centerLng = input.pin.longitude;

    // 그룹 핀 드로잉 (최대 16개 — 시각 혼잡 방지)
    const groupPinsToRender = (input.groupPins ?? [])
      .filter((p) => p.id !== input.pin.id)
      .slice(0, 16);

    for (const gp of groupPinsToRender) {
      if (Math.abs(gp.longitude - centerLng) > 180) continue; // antimeridian skip

      const { x: apiX, y: apiY } = geoToApiPixel(
        gp.latitude, gp.longitude,
        centerLat, centerLng,
        MAPBOX_ZOOM, MAPBOX_API_WIDTH, MAPBOX_API_HEIGHT,
      );
      const cx = apiX * SCALE_X;
      const cy = apiY * SCALE_Y;

      if (cx < -60 || cx > CARD_WIDTH + 60 || cy < -60 || cy > CARD_HEIGHT + 60) continue;

      ctxA.beginPath();
      ctxA.arc(cx, cy, BG_RADIUS_OTHER, 0, 2 * Math.PI);
      ctxA.fillStyle = "rgba(255,255,255,0.85)";
      ctxA.fill();

      const glyphImg = otherGlyphMap[gp.tag as PinTag];
      if (glyphImg) {
        ctxA.drawImage(
          glyphImg,
          cx - GLYPH_SIZE_OTHER / 2, cy - GLYPH_SIZE_OTHER / 2,
          GLYPH_SIZE_OTHER, GLYPH_SIZE_OTHER,
        );
      }
    }

    // 자기 핀 드로잉 — 최상위 레이어 (canvasA 마지막)
    // self pin은 항상 center이므로 카드 중앙 좌표로 직접 계산
    {
      const cx = CARD_WIDTH / 2;
      const cy = CARD_HEIGHT / 2;

      ctxA.beginPath();
      ctxA.arc(cx, cy, BG_RADIUS_SELF, 0, 2 * Math.PI);
      ctxA.fillStyle = "rgba(255,255,255,0.95)";
      ctxA.fill();

      const selfTag = input.pin.tag as PinTag;
      const selfGlyph = selfGlyphMap[selfTag];
      if (selfGlyph) {
        ctxA.drawImage(
          selfGlyph,
          cx - GLYPH_SIZE_SELF / 2, cy - GLYPH_SIZE_SELF / 2,
          GLYPH_SIZE_SELF, GLYPH_SIZE_SELF,
        );
      }
    }
  }

  // Step 5 — Off-canvas B에 blur(4px) 합성 — 동네 식별 가능 수준의 약한 흐림
  const canvasB = document.createElement("canvas");
  canvasB.width = CARD_WIDTH;
  canvasB.height = CARD_HEIGHT;
  const ctxB = canvasB.getContext("2d");
  if (!ctxB) {
    throw new Error("CANVAS_UNSUPPORTED");
  }
  ctxB.filter = "blur(2px)";
  ctxB.drawImage(canvasA, 0, 0);
  ctxB.filter = "none";
  // A 메모리 즉시 해제
  canvasA.width = 0;
  canvasA.height = 0;

  // Step 6 — 메인 canvas에 합성
  const main = document.createElement("canvas");
  main.width = CARD_WIDTH;
  main.height = CARD_HEIGHT;
  const ctx = main.getContext("2d");
  if (!ctx) {
    throw new Error("CANVAS_UNSUPPORTED");
  }
  ctx.drawImage(canvasB, 0, 0);
  // 콘텐츠 가독성 확보용 따뜻한 베이지 톤 overlay (40%). 도로·건물 음영은 살짝 비치도록.
  ctx.fillStyle = "rgba(234, 228, 212, 0.42)";
  ctx.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT);
  canvasB.width = 0;
  canvasB.height = 0;

  throwIfAborted(signal);

  // Step 7-0 — 태그 글리프 Image 로드 (장소명 좌측에 작은 글리프로 합성). 실패 시 skip.
  const TAG_GLYPH_SIZE = 28;
  let tagGlyphImg: HTMLImageElement | null = null;
  let tagGlyphSvgUrl: string | null = null;
  {
    const tag = input.pin.tag;
    let glyphSvg = "";
    if (tag === "REEL") glyphSvg = getReelSvgString(TAG_GLYPH_SIZE);
    else if (tag === "WISH") glyphSvg = getWishSvgString(TAG_GLYPH_SIZE);
    else if (tag === "MEMORY")
      glyphSvg = getMemorySvgString(TAG_GLYPH_SIZE, TAG_GLYPH_SIZE);
    if (glyphSvg) {
      const svgBlob = new Blob([glyphSvg], { type: "image/svg+xml" });
      tagGlyphSvgUrl = URL.createObjectURL(svgBlob);
      const img = new Image();
      img.src = tagGlyphSvgUrl;
      try {
        await new Promise<void>((resolve, reject) => {
          img.onload = () => resolve();
          img.onerror = () => reject();
        });
        tagGlyphImg = img;
      } catch {
        // 로드 실패 → 글리프 없이 카드 진행
      }
    }
  }

  // Step 7 — 콘텐츠 텍스트 그리기 (FR-6, BR-1/2/3/4/9)
  ctx.textBaseline = "alphabetic";

  let cursorY = CONTENT_START_Y;

  // 메모 (BR-2)
  if (input.pin.memo && input.pin.memo.length > 0) {
    ctx.font = fontStr(`${FONT_MEMO_PX}px`, fontFamily);
    ctx.fillStyle = COLOR_MEMO;
    const memoLines = wrapAndEllipsize(
      input.pin.memo,
      CONTENT_MAX_WIDTH,
      5,
      (s) => ctx.measureText(s).width,
    );
    memoLines.forEach((line, idx) => {
      ctx.fillText(line, PADDING_X, cursorY + (idx + 1) * LINE_HEIGHT_MEMO);
    });
    cursorY += memoLines.length * LINE_HEIGHT_MEMO + GAP_AFTER_MEMO;
  }

  // 장소명 (BR-3) — 첫 줄 좌측에 핀 태그 글리프(28×28) 합성. 라벨 없이 글리프만.
  ctx.font = fontStr(`bold ${FONT_PLACE_PX}px`, fontFamily);
  ctx.fillStyle = COLOR_PLACE_NAME;
  const GLYPH_TEXT_GAP = 10;
  const placeFirstLineX =
    PADDING_X + (tagGlyphImg ? TAG_GLYPH_SIZE + GLYPH_TEXT_GAP : 0);
  const placeMaxWidthFirstLine = tagGlyphImg
    ? CONTENT_MAX_WIDTH - TAG_GLYPH_SIZE - GLYPH_TEXT_GAP
    : CONTENT_MAX_WIDTH;
  const placeLines = wrapAndEllipsize(
    input.pin.placeName,
    placeMaxWidthFirstLine,
    2,
    (s) => ctx.measureText(s).width,
  );
  placeLines.forEach((line, idx) => {
    const lineX = idx === 0 ? placeFirstLineX : PADDING_X;
    ctx.fillText(line, lineX, cursorY + (idx + 1) * LINE_HEIGHT_PLACE);
  });
  // 글리프는 장소명 첫 줄과 시각 중앙 정렬 (cap height 중앙 ≈ baseline - 13)
  if (tagGlyphImg) {
    const firstBaseline = cursorY + LINE_HEIGHT_PLACE;
    const glyphY = firstBaseline - 13 - TAG_GLYPH_SIZE / 2;
    ctx.drawImage(
      tagGlyphImg,
      PADDING_X,
      glyphY,
      TAG_GLYPH_SIZE,
      TAG_GLYPH_SIZE,
    );
  }
  cursorY += placeLines.length * LINE_HEIGHT_PLACE + GAP_AFTER_PLACE;

  // 날짜 · 주소 (BR-9)
  const dateAddress = input.pin.address
    ? `${input.formattedDate} · ${input.pin.address}`
    : input.formattedDate;
  ctx.font = fontStr(`${FONT_META_PX}px`, fontFamily);
  ctx.fillStyle = COLOR_META;
  const dateLines = wrapAndEllipsize(
    dateAddress,
    CONTENT_MAX_WIDTH,
    1,
    (s) => ctx.measureText(s).width,
  );
  ctx.fillText(dateLines[0] ?? "", PADDING_X, cursorY + LINE_HEIGHT_META);
  cursorY += LINE_HEIGHT_META + GAP_AFTER_DATE;

  // 작성자
  ctx.font = fontStr(`${FONT_META_PX}px`, fontFamily);
  ctx.fillStyle = COLOR_META;
  ctx.fillText(
    `written by ${input.authorLabel}`,
    PADDING_X,
    cursorY + LINE_HEIGHT_META,
  );

  // 워터마크 (좌하단 고정)
  ctx.font = fontStr(`bold ${FONT_WATERMARK_PX}px`, fontFamily);
  ctx.fillStyle = COLOR_WATERMARK;
  ctx.textBaseline = "alphabetic";
  ctx.fillText("우리가갈지도", PADDING_X, CARD_HEIGHT - WATERMARK_BOTTOM_OFFSET);

  throwIfAborted(signal);

  // Step 9 — toBlob + 메모리 정리
  const blob = await new Promise<Blob>((resolve, reject) => {
    main.toBlob((b) => {
      if (b) {
        resolve(b);
      } else {
        reject(new Error("toBlob failed"));
      }
    }, "image/png");
  });
  // 메인 캔버스 메모리 즉시 해제
  main.width = 0;
  main.height = 0;
  // 태그 글리프 SVG URL 해제 (Step 7-0에서 생성한 Blob URL)
  if (tagGlyphSvgUrl) {
    URL.revokeObjectURL(tagGlyphSvgUrl);
    if (tagGlyphImg) tagGlyphImg.src = "";
  }

  const previewDataUrl = URL.createObjectURL(blob);
  return { blob, previewDataUrl };
}
