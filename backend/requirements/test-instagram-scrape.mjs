/**
 * Instagram Reels 스크래핑 가능성 테스트
 * 방법 1: oEmbed API (무료, 키 불필요)
 * 방법 2: HTML meta tag 파싱 (폴백)
 */

const TEST_URL = process.argv[2] || "https://www.instagram.com/reel/C-example/";

// ─── 방법 1: oEmbed API ───────────────────────────────────────────────────────
async function testOEmbed(url) {
  console.log("\n[방법 1] Instagram oEmbed API 테스트");
  console.log("URL:", url);

  const oembedUrl = `https://api.instagram.com/oembed/?url=${encodeURIComponent(url)}&omitscript=true`;

  try {
    const res = await fetch(oembedUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 (compatible; bot/1.0)",
      },
    });

    console.log("HTTP 상태:", res.status, res.statusText);

    if (!res.ok) {
      const text = await res.text();
      console.log("응답 본문:", text.slice(0, 300));
      return null;
    }

    const data = await res.json();
    console.log("\n✅ oEmbed 성공! 수신 데이터:");
    console.log(JSON.stringify(data, null, 2));
    return data;
  } catch (err) {
    console.log("❌ oEmbed 실패:", err.message);
    return null;
  }
}

function decodeHtmlEntities(str) {
  return str
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => String.fromCodePoint(parseInt(dec, 10)))
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

// ─── 방법 2: HTML meta 태그 파싱 ─────────────────────────────────────────────
async function testMetaScrape(url) {
  console.log("\n[방법 2] HTML meta 태그 직접 파싱 테스트");

  try {
    const res = await fetch(url, {
      headers: {
        "User-Agent":
          "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        Accept: "text/html,application/xhtml+xml",
        "Accept-Language": "ko-KR,ko;q=0.9",
      },
      redirect: "follow",
    });

    console.log("HTTP 상태:", res.status, res.statusText);

    const html = await res.text();
    console.log("HTML 크기:", html.length, "bytes");

    const rawPatterns = {
      "og:title": /<meta property="og:title" content="([^"]*)"/.exec(html),
      "og:description": /<meta property="og:description" content="([^"]*)"/.exec(html),
      "og:image": /<meta property="og:image" content="([^"]*)"/.exec(html),
    };

    console.log("\n📦 디코딩된 meta 태그:");
    let found = false;
    for (const [key, match] of Object.entries(rawPatterns)) {
      if (match) {
        const decoded = decodeHtmlEntities(match[1]);
        console.log(`  [${key}]\n  ${decoded.slice(0, 300)}\n`);
        found = true;
      }
    }

    if (!found) {
      console.log("  ❌ meta 태그 없음 (로그인 리다이렉트 또는 봇 차단)");
      console.log("\n수신된 HTML 앞 500자:");
      console.log(html.slice(0, 500));
      return null;
    }

    // og:description에서 캡션 추출
    const descMatch = /<meta property="og:description" content="([^"]*)"/.exec(html);
    if (descMatch) {
      const desc = decodeHtmlEntities(descMatch[1]);
      // "456 likes, 77 comments - yennieday2 - May 13, 2026: "캡션내용""
      const captionMatch = /:\s*"(.+)"$/.exec(desc.trim());
      const caption = captionMatch ? captionMatch[1] : desc;

      console.log("✅ 추출된 캡션 (AI로 장소명 뽑을 텍스트):");
      console.log("  >>", caption);
      return caption;
    }
  } catch (err) {
    console.log("❌ HTML 파싱 실패:", err.message);
  }
  return null;
}

// ─── 메인 ─────────────────────────────────────────────────────────────────────
console.log("=".repeat(60));
console.log("Instagram 스크래핑 가능성 테스트");
console.log("=".repeat(60));
console.log("\n사용법: node test-instagram-scrape.mjs <릴스_URL>");
console.log("예시:   node test-instagram-scrape.mjs https://www.instagram.com/reel/ABC123/\n");

const result = await testOEmbed(TEST_URL);
await testMetaScrape(TEST_URL);

console.log("\n" + "=".repeat(60));
console.log("결과 요약");
console.log("=".repeat(60));
if (result) {
  console.log("✅ oEmbed API: 동작함 → 캡션/해시태그 추출 가능");
  console.log("   다음 단계: OpenAI로 장소명 추출 테스트");
} else {
  console.log("⚠️  oEmbed API: 미동작");
  console.log("   → Apify 또는 다른 방법 필요");
}
