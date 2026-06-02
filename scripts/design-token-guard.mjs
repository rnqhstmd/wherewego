// design-token-guard.mjs — 디자인 토큰 색상 drift 가드 (FR-1/FR-2/AC-1~4)
//
// 목적:
//   웹 frontend/src/lib/design/tokens.ts 의 `colors`(22키)와
//   iOS ios/WhereWeGo/Core/DesignSystem/Theme.swift 의 `WGColor`(22키)를
//   비교해 색상 값/키 drift 를 감지한다. 두 플랫폼의 색이 어긋나면 CI 가 깨지도록
//   non-zero exit 으로 종료한다.
//
// 특징:
//   - 의존성 0 (node:fs / node:url 만 사용), ESM(.mjs).
//   - import.meta.url 기준 절대경로화 → 어느 CWD 에서도 동작.
//   - 폰트 토큰은 비교 대상이 아니다(색상만).
//
// 실행법:
//   node scripts/design-token-guard.mjs            # 실파일 비교 (AC-1/AC-2/AC-4)
//   node scripts/design-token-guard.mjs --selftest # 인메모리 fixture 로 로직 검증 (AC-2~AC-4)

import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";

const WEB_PATH = fileURLToPath(
  new URL("../frontend/src/lib/design/tokens.ts", import.meta.url),
);
const IOS_PATH = fileURLToPath(
  new URL("../ios/WhereWeGo/Core/DesignSystem/Theme.swift", import.meta.url),
);

// ── 파서 ──────────────────────────────────────────────────────────────────

/** tokens.ts 의 `colors` 블록을 파싱한다(fonts 블록은 제외). */
export function parseWebColors(src) {
  const map = new Map();
  src = String(src).replace(/\r\n/g, "\n"); // CRLF 정규화(Windows autocrlf 대비)
  // `export const colors` 앵커로 다른 `colors`/`fonts` 블록과의 혼동 방지.
  const block = src.match(/export\s+const\s+colors\s*=\s*\{([\s\S]*?)\}\s*as\s+const/);
  if (!block) return map;
  const lineRe = /^\s*(\w+)\s*:\s*"([^"]+)"/gm;
  let m;
  while ((m = lineRe.exec(block[1])) !== null) {
    map.set(m[1], m[2]);
  }
  return map;
}

/** Theme.swift 의 `enum WGColor` 블록을 파싱한다(extension Color 의 init 은 enum 밖이라 배제됨). */
export function parseIosColors(src) {
  const map = new Map();
  src = String(src).replace(/\r\n/g, "\n"); // CRLF 정규화(Windows autocrlf 시 `\n}` 매칭 깨짐 방지)
  // 한계: 첫 '\n}'에서 블록 종료. 현재 WGColor는 단순 static let 나열이라 안전하나, 내부에 중괄호(computed property/헬퍼) 추가 시 조기 종료 위험.
  const block = src.match(/enum\s+WGColor\s*\{([\s\S]*?)\n\}/);
  if (!block) return map;
  // 정렬용 다중 공백을 \s+ 로 흡수.
  const lineRe = /static\s+let\s+(\w+)\s*=\s*Color\((.+)\)/g;
  let m;
  while ((m = lineRe.exec(block[1])) !== null) {
    map.set(m[1], m[2].trim());
  }
  return map;
}

// ── 정규화 ────────────────────────────────────────────────────────────────

/**
 * 색상 raw 문자열을 {r,g,b,a} 로 정규화한다(형식 자동 감지).
 * r,g,b: 0..255 정수 / a: 0..1 실수(3자리 반올림).
 * 지원 형식:
 *   1) #RRGGBB              → a:1
 *   2) #RRGGBBAA            → 끝 2자리/255 = a
 *   3) rgba(R,G,B,A)        → 공백 허용
 *   4) rgb(R,G,B)           → a:1
 *   5) iOS hex:"#RRGGBB"               → a:1
 *   6) iOS hex:"#RRGGBB", opacity:N    → a:N
 */
export function normalize(raw) {
  const s = String(raw).trim();

  // 5/6) iOS Color(...) 인자: hex:"..." [, opacity:N]
  const iosArg = s.match(
    /hex:\s*"([^"]+)"(?:\s*,\s*opacity:\s*([\d.]+))?/i,
  );
  if (iosArg) {
    const base = hexToRgba(iosArg[1]);
    const a = iosArg[2] !== undefined ? round3(parseFloat(iosArg[2])) : base.a;
    return { r: base.r, g: base.g, b: base.b, a };
  }

  // 3) rgba(R,G,B,A) — 공백 허용
  const rgba = s.match(
    /rgba\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)/i,
  );
  if (rgba) {
    return {
      r: parseInt(rgba[1], 10),
      g: parseInt(rgba[2], 10),
      b: parseInt(rgba[3], 10),
      a: round3(parseFloat(rgba[4])),
    };
  }

  // 4) rgb(R,G,B)
  const rgb = s.match(/rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)/i);
  if (rgb) {
    return {
      r: parseInt(rgb[1], 10),
      g: parseInt(rgb[2], 10),
      b: parseInt(rgb[3], 10),
      a: 1,
    };
  }

  // 1/2) #RRGGBB | #RRGGBBAA
  const hex = s.match(/#?([0-9a-f]{6}(?:[0-9a-f]{2})?)/i);
  if (hex) return hexToRgba(hex[1]);

  throw new Error(`정규화 불가: ${raw}`);
}

/** "#RRGGBB" | "#RRGGBBAA" (또는 #없이) → {r,g,b,a}. 대소문자 무시. */
function hexToRgba(input) {
  const h = String(input).replace(/^#/, "");
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  const a = h.length === 8 ? round3(parseInt(h.slice(6, 8), 16) / 255) : 1;
  return { r, g, b, a };
}

function round3(n) {
  return Math.round(n * 1000) / 1000;
}

/** 두 정규화 색이 동등한가(r/g/b 정수 일치, a 는 0.001 허용오차). */
function colorsEqual(a, b) {
  return a.r === b.r && a.g === b.g && a.b === b.b && Math.abs(a.a - b.a) < 0.001;
}

// ── 비교 ──────────────────────────────────────────────────────────────────

/**
 * 웹/iOS 색상 맵을 비교한다.
 * @returns { missingInIOS[], missingInWeb[], mismatched[] }
 *   mismatched: [{ key, webRaw, web, iosRaw, ios }]
 */
export function compare(web, ios) {
  const missingInIOS = [];
  const missingInWeb = [];
  const mismatched = [];

  for (const [key, webRaw] of web) {
    if (!ios.has(key)) {
      missingInIOS.push(key);
      continue;
    }
    const iosRaw = ios.get(key);
    const w = normalize(webRaw);
    const i = normalize(iosRaw);
    if (!colorsEqual(w, i)) {
      mismatched.push({ key, webRaw, web: w, iosRaw, ios: i });
    }
  }
  for (const key of ios.keys()) {
    if (!web.has(key)) missingInWeb.push(key);
  }

  return { missingInIOS, missingInWeb, mismatched };
}

// ── selftest (인메모리 fixture, AC-2~AC-4 재현) ─────────────────────────────

function runSelftest() {
  const cases = [];

  // ① 색상 1개 변조 → mismatch 감지 (AC-2)
  {
    const web = new Map([["cta", "#C4622D"]]);
    const ios = new Map([["cta", 'hex: "#A84E23"']]);
    const r = compare(web, ios);
    cases.push([
      "① 색상 변조 → mismatch",
      r.mismatched.length === 1 &&
        r.mismatched[0].key === "cta" &&
        r.missingInIOS.length === 0 &&
        r.missingInWeb.length === 0,
    ]);
  }

  // ② 키 1개 제거 → missing 감지 (AC-4)
  {
    const web = new Map([
      ["bg", "#FAF8F5"],
      ["panel", "#FFFFFF"],
    ]);
    const ios = new Map([["bg", 'hex: "#FAF8F5"']]);
    const r = compare(web, ios);
    cases.push([
      "② 키 제거 → missing",
      r.missingInIOS.length === 1 &&
        r.missingInIOS[0] === "panel" &&
        r.mismatched.length === 0,
    ]);
  }

  // ③ rgba(26,26,46,0.08) ↔ #1A1A2E / opacity 0.08 → 동등 (AC-3)
  {
    const web = new Map([["shadow", "rgba(26,26,46,0.08)"]]);
    const ios = new Map([["shadow", 'hex: "#1A1A2E", opacity: 0.08']]);
    const r = compare(web, ios);
    cases.push([
      "③ rgba(no-space) ↔ hex+opacity → match",
      r.mismatched.length === 0 &&
        r.missingInIOS.length === 0 &&
        r.missingInWeb.length === 0,
    ]);
  }

  // ④ 공백 포함 rgba(26, 26, 46, 0.08) ↔ #1A1A2E / 0.08 → 동등 (AC-3)
  {
    const web = new Map([["shadow", "rgba(26, 26, 46, 0.08)"]]);
    const ios = new Map([["shadow", 'hex: "#1A1A2E", opacity: 0.08']]);
    const r = compare(web, ios);
    cases.push([
      "④ rgba(space) ↔ hex+opacity → match",
      r.mismatched.length === 0,
    ]);
  }

  // ⑤ iOS 에만 있는 키 → missingInWeb 감지 (FR-2 양방향)
  {
    const web = new Map([["bg", "#FAF8F5"]]);
    const ios = new Map([
      ["bg", 'hex: "#FAF8F5"'],
      ["extra", 'hex: "#000000"'],
    ]);
    const r = compare(web, ios);
    cases.push([
      "⑤ iOS 잉여 키 → missingInWeb",
      r.missingInWeb.length === 1 &&
        r.missingInWeb[0] === "extra" &&
        r.mismatched.length === 0,
    ]);
  }

  let ok = true;
  for (const [name, pass] of cases) {
    if (!pass) {
      console.error(`✗ selftest 실패: ${name}`);
      ok = false;
    }
  }
  return ok;
}

// ── main ──────────────────────────────────────────────────────────────────

function main() {
  if (process.argv.includes("--selftest")) {
    const ok = runSelftest();
    if (ok) {
      console.log("✓ selftest 통과");
      process.exit(0);
    }
    process.exit(1);
  }

  const webSrc = readFileSync(WEB_PATH, "utf8");
  const iosSrc = readFileSync(IOS_PATH, "utf8");
  const web = parseWebColors(webSrc);
  const ios = parseIosColors(iosSrc);

  let result;
  try {
    result = compare(web, ios);
  } catch (err) {
    console.error("✗ 지원하지 않는 색상 형식: " + err.message);
    process.exit(1);
  }
  const { missingInIOS, missingInWeb, mismatched } = result;

  let failed = false;

  if (missingInIOS.length > 0) {
    console.error(
      `✗ 키 누락 — Theme.swift에 없음: [${missingInIOS.join(", ")}]`,
    );
    failed = true;
  }
  if (missingInWeb.length > 0) {
    console.error(`✗ 키 누락 — tokens.ts에 없음: [${missingInWeb.join(", ")}]`);
    failed = true;
  }
  for (const mm of mismatched) {
    console.error(
      `✗ ${mm.key}: web=${mm.webRaw} ${fmt(mm.web)} vs ios=${mm.iosRaw} ${fmt(mm.ios)}`,
    );
    failed = true;
  }

  if (failed) process.exit(1);

  console.log(`✓ 색상 ${web.size}/${ios.size} 키 일치`);
  process.exit(0);
}

function fmt({ r, g, b, a }) {
  return `{r:${r},g:${g},b:${b},a:${a}}`;
}

// 직접 실행 시에만 main() 호출(import 시 부작용 방지 → 테스트/재사용 가능).
// process.argv[1] 부재(node -e 등) 시 안전하게 건너뛴다.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
