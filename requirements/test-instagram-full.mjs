/**
 * Instagram 스크래핑 - 뽑을 수 있는 모든 데이터 조사
 */

const url = process.argv[2];
if (!url) {
  console.log("사용법: node test-instagram-full.mjs <인스타_URL>");
  process.exit(1);
}

const res = await fetch(url, {
  headers: {
    "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
    Accept: "text/html,application/xhtml+xml",
    "Accept-Language": "ko-KR,ko;q=0.9",
  },
  redirect: "follow",
});

const html = await res.text();

function decode(str) {
  return str
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}

function getMeta(property) {
  const m = new RegExp(`<meta property="${property}" content="([^"]*)"`, 'i').exec(html);
  return m ? decode(m[1]) : null;
}

function getMetaName(name) {
  const m = new RegExp(`<meta name="${name}" content="([^"]*)"`, 'i').exec(html);
  return m ? decode(m[1]) : null;
}

// ─── 1. og 메타 태그 ───────────────────────────────────────────────
console.log("=".repeat(60));
console.log("1. OG 메타 태그 (기본 스크래핑)");
console.log("=".repeat(60));
const ogTitle = getMeta("og:title");
const ogDesc = getMeta("og:description");
const ogImage = getMeta("og:image");
const ogVideo = getMeta("og:video");
const ogVideoUrl = getMeta("og:video:secure_url");

console.log("og:title   :", ogTitle);
console.log("og:desc    :", ogDesc?.slice(0, 200));
console.log("og:image   :", ogImage ? "✅ 썸네일 URL 있음" : "❌ 없음");
console.log("og:video   :", ogVideo ? "✅ 영상 URL 있음" : "❌ 없음");

// ─── 2. 캡션 파싱 ─────────────────────────────────────────────────
console.log("\n" + "=".repeat(60));
console.log("2. 캡션 파싱 결과");
console.log("=".repeat(60));

const caption = ogDesc?.replace(/^\d[\d,]* likes.*?\d{4}: "/, '').replace(/"\.?\s*$/, '').trim();
console.log("캡션:\n" + caption);

// 위치 힌트 패턴
const locationPatterns = [
  { label: "📍 이모지", regex: /📍\s*(.+)/gm },
  { label: "@위치태그", regex: /@([가-힣a-zA-Z0-9_]+)/g },
  { label: "위치/장소 키워드", regex: /(?:위치|장소|여기|가게|식당|카페)(?:는|은|:)?\s*([^\n,]+)/gm },
];

console.log("\n위치 힌트 탐색:");
for (const { label, regex } of locationPatterns) {
  const matches = [...(caption?.matchAll(regex) || [])];
  if (matches.length > 0) {
    console.log(`  [${label}]`, matches.map(m => m[1] || m[0]).join(', '));
  }
}

// 해시태그
const hashtags = [...(caption?.matchAll(/#([^\s#]+)/g) || [])].map(m => '#' + m[1]);
console.log("  [해시태그]", hashtags.join(' ') || "없음");

// ─── 3. HTML에 위치 태그 데이터 있는지 탐색 ──────────────────────
console.log("\n" + "=".repeat(60));
console.log("3. 인스타그램 위치 태그 (Location Tag) 탐색");
console.log("=".repeat(60));

// 인스타는 location을 JSON 스크립트 블록에 숨겨둠
const locationInJson = /"location"\s*:\s*\{[^}]+\}/g.exec(html);
const locationName = /"location_name"\s*:\s*"([^"]+)"/.exec(html);
const placeName = /"place_name"\s*:\s*"([^"]+)"/.exec(html);
const addressLine = /"address_line_1"\s*:\s*"([^"]+)"/.exec(html);

console.log("location_name :", locationName ? decode(locationName[1]) : "❌ 없음");
console.log("place_name    :", placeName ? decode(placeName[1]) : "❌ 없음");
console.log("address_line  :", addressLine ? decode(addressLine[1]) : "❌ 없음");

// ─── 4. 썸네일로 Vision 분석 가능 여부 ───────────────────────────
console.log("\n" + "=".repeat(60));
console.log("4. 썸네일 이미지 Vision 분석 가능 여부");
console.log("=".repeat(60));
if (ogImage) {
  console.log("✅ 썸네일 URL 확보됨");
  console.log("   → GPT-4o Vision으로 이미지 내 텍스트(상호명) OCR 가능");
  console.log("   URL:", ogImage.slice(0, 100) + "...");
} else {
  console.log("❌ 썸네일 없음");
}

// ─── 5. 종합 ─────────────────────────────────────────────────────
console.log("\n" + "=".repeat(60));
console.log("5. 종합: 장소명 추출 가능 여부");
console.log("=".repeat(60));

const hasLocationEmoji = /📍/.test(caption || '');
const hasPlaceKeyword = /(?:위치|장소|여기|가게|식당|카페)/.test(caption || '');
const hasLocationTag = !!(locationName || placeName);

console.log(`캡션 내 📍 이모지: ${hasLocationEmoji ? '✅' : '❌'}`);
console.log(`캡션 내 장소 키워드: ${hasPlaceKeyword ? '✅' : '❌'}`);
console.log(`인스타 위치 태그: ${hasLocationTag ? '✅' : '❌'}`);
console.log(`썸네일 Vision OCR: ✅ (항상 가능)`);
