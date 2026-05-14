package com.wherewego.spike.instagram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인스타 릴스 메타 스크래핑 spike 실행기.
 * 설계서 §3.4 (AC-17, 18, 19).
 *
 * 흐름:
 *   1) sample-urls.txt에서 URL 라인 읽기
 *   2) 각 URL에 3단계(NO_UA, CHROME_UA, FULL_HEADERS) 순차 시도
 *   3) HTML 응답을 samples/<shortcode>__<strategy>.html로 저장
 *   4) 단계별/전체 통계 누적
 *   5) result.md를 통계로 갱신
 */
public class InstagramMetaSpikeRunner {

    private static final Pattern SHORTCODE_PATTERN = Pattern.compile("/reel/([\\w-]+)/?");

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: InstagramMetaSpikeRunner <sample-urls.txt> <result.md>");
            System.exit(1);
        }

        Path urlsPath = Paths.get(args[0]);
        Path resultPath = Paths.get(args[1]);
        // runSpike의 working directory는 subproject root이므로 samples는 거기에 만든다 (설계서 §3.4.1)
        Path samplesDir = Paths.get("samples");
        Files.createDirectories(samplesDir);

        List<String> urls = readUrls(urlsPath);
        if (urls.isEmpty()) {
            System.err.println("[WARN] sample-urls.txt가 비어 있습니다. 100개 URL을 채운 뒤 다시 실행하세요.");
            writeResultMarkdown(resultPath, 0, new Stats(), false);
            return;
        }

        HtmlFetcher fetcher = new HtmlFetcher();
        MetaExtractor metaExtractor = new MetaExtractor();
        PlaceNameExtractor placeExtractor = new PlaceNameExtractor();

        Stats stats = new Stats();
        int total = urls.size();

        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            System.out.printf(Locale.ROOT, "[%d/%d] %s%n", i + 1, total, url);
            String shortcode = extractShortcode(url, i);

            boolean extractedForUrl = false;
            for (HtmlFetcher.Strategy strategy : HtmlFetcher.Strategy.values()) {
                HtmlFetcher.FetchResult fr = fetcher.fetch(url, strategy);
                stats.recordAttempt(strategy, fr.blocked);

                saveHtml(samplesDir, shortcode, strategy, fr.body);

                if (fr.blocked) {
                    continue;
                }

                MetaExtractor.MetaResult meta = metaExtractor.extract(fr.body);
                if (!meta.ogDescription.isBlank()) {
                    stats.recordOgValid(strategy);
                }

                Optional<PlaceNameExtractor.ExtractionResult> extraction = placeExtractor.extract(meta.ogDescription);
                if (extraction.isPresent() && !extractedForUrl) {
                    stats.recordExtraction(extraction.get().matchedPattern);
                    extractedForUrl = true;
                    break;
                }
            }
        }

        writeResultMarkdown(resultPath, total, stats, true);
        System.out.println("[DONE] result.md 갱신 완료: " + resultPath.toAbsolutePath());
    }

    private static List<String> readUrls(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            urls.add(trimmed);
        }
        return urls;
    }

    private static String extractShortcode(String url, int fallbackIndex) {
        Matcher m = SHORTCODE_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return "url-" + fallbackIndex;
    }

    private static void saveHtml(Path samplesDir, String shortcode, HtmlFetcher.Strategy strategy, String body) {
        Path target = samplesDir.resolve(shortcode + "__" + strategy.name() + ".html");
        try {
            Files.writeString(target, body == null ? "" : body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[WARN] HTML 저장 실패: " + target + " - " + e.getMessage());
        }
    }

    private static void writeResultMarkdown(Path resultPath, int total, Stats stats, boolean executed) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 인스타 릴스 메타 스크래핑 Spike 결과\n\n");
        sb.append("설계서: `.dev/feature-phase-0-foundation/design.md` §3.4\n\n");

        // 법적 리스크 섹션은 runSpike가 result.md를 덮어쓸 때마다 영구 포함되어야 한다.
        // (Phase 0 cross-review Info 항목: 사용자 실행 시 result.md 통째 덮어쓰기로
        //  상단 법적 리스크 섹션이 소실될 위험 → 템플릿에 영구 포함하는 방식으로 보존)
        sb.append("## 법적 리스크\n\n");
        sb.append("Meta Instagram ToS §II는 자동화된 데이터 수집(scraping)을 금지합니다. ");
        sb.append("본 spike의 `CHROME_UA`/`FULL_HEADERS` 전략은 브라우저 위장으로 차단을 우회하는 ");
        sb.append("의도가 있어 ToS 위반 + CFAA 등 법적 대응 대상이 될 수 있습니다.\n\n");
        sb.append("- **운영 코드 적용 금지**: spike는 기술 검증 목적으로만 사용. 본 코드는 그대로 운영 코드에 통합하지 마십시오.\n");
        sb.append("- **대안 검토**: Apify Instagram Scraper, Instagram Basic Display API(공식, OAuth 필요), 사용자 직접 입력 폴백.\n");
        sb.append("- **Phase 8 진입 전 법무 검토 필수**.\n\n");

        sb.append("## 실행 요약\n\n");

        if (!executed) {
            sb.append("- 상태: _(미실행)_\n");
            sb.append("- 총 URL 수: _(미실행)_\n");
            sb.append("- 단계별 차단율: _(미실행)_\n");
            sb.append("- og:description 유효율: _(미실행)_\n");
            sb.append("- 장소명 추출 성공률: _(미실행)_\n");
            sb.append("- 패턴별 기여도: _(미실행)_\n\n");
        } else {
            sb.append("- 상태: 실행 완료\n");
            sb.append("- 총 URL 수: ").append(total).append("\n");
            sb.append("- 장소명 추출 성공률: ")
                    .append(formatPercent(stats.totalExtractions(), total))
                    .append(" (")
                    .append(stats.totalExtractions())
                    .append("/")
                    .append(total)
                    .append(")\n\n");
        }

        sb.append("## 단계별 차단율 (AC-18)\n\n");
        sb.append("| Strategy | 시도 | 차단 | 차단율 | og:desc 유효 |\n");
        sb.append("|----------|------|------|--------|--------------|\n");
        if (!executed) {
            sb.append("| NO_UA | _(미실행)_ | _(미실행)_ | _(미실행)_ | _(미실행)_ |\n");
            sb.append("| CHROME_UA | _(미실행)_ | _(미실행)_ | _(미실행)_ | _(미실행)_ |\n");
            sb.append("| FULL_HEADERS | _(미실행)_ | _(미실행)_ | _(미실행)_ | _(미실행)_ |\n\n");
        } else {
            for (HtmlFetcher.Strategy s : HtmlFetcher.Strategy.values()) {
                int attempts = stats.attempts.getOrDefault(s, 0);
                int blocked = stats.blocked.getOrDefault(s, 0);
                int ogValid = stats.ogValid.getOrDefault(s, 0);
                sb.append("| ").append(s.name())
                        .append(" | ").append(attempts)
                        .append(" | ").append(blocked)
                        .append(" | ").append(formatPercent(blocked, attempts))
                        .append(" | ").append(ogValid).append(" (").append(formatPercent(ogValid, attempts)).append(")")
                        .append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## 패턴별 기여도 (AC-19)\n\n");
        sb.append("| 패턴 | 매칭 수 | 비율 |\n");
        sb.append("|------|---------|------|\n");
        if (!executed) {
            sb.append("| EMOJI_PIN | _(미실행)_ | _(미실행)_ |\n");
            sb.append("| KEYWORD | _(미실행)_ | _(미실행)_ |\n");
            sb.append("| HASHTAG | _(미실행)_ | _(미실행)_ |\n\n");
        } else {
            int totalExtractions = stats.totalExtractions();
            for (String pattern : List.of("EMOJI_PIN", "KEYWORD", "HASHTAG")) {
                int count = stats.patternMatches.getOrDefault(pattern, 0);
                sb.append("| ").append(pattern)
                        .append(" | ").append(count)
                        .append(" | ").append(formatPercent(count, totalExtractions))
                        .append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## 결론 및 다음 단계\n\n");
        sb.append("- AC-17 (실행 가능): runSpike Gradle 태스크 등록 완료\n");
        sb.append("- AC-18 (차단율 측정): 위 단계별 차단율 표 참조\n");
        sb.append("- AC-19 (패턴 기여도): 위 패턴별 기여도 표 참조\n");
        sb.append("- 차단율 >30% 시: ADR-0001 재도입 트리거 발동 → Kafka/Redis 기반 비동기 처리 검토\n");
        sb.append("- Phase 8 PRD 갱신 권고: 본 결과 링크 추가 (구현 순서 Wave 7)\n");

        Files.writeString(resultPath, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String formatPercent(int numerator, int denominator) {
        if (denominator <= 0) {
            return "0.0%";
        }
        double pct = (numerator * 100.0) / denominator;
        return String.format(Locale.ROOT, "%.1f%%", pct);
    }

    /** 누적 통계. */
    private static class Stats {
        final Map<HtmlFetcher.Strategy, Integer> attempts = new EnumMap<>(HtmlFetcher.Strategy.class);
        final Map<HtmlFetcher.Strategy, Integer> blocked = new EnumMap<>(HtmlFetcher.Strategy.class);
        final Map<HtmlFetcher.Strategy, Integer> ogValid = new EnumMap<>(HtmlFetcher.Strategy.class);
        final Map<String, Integer> patternMatches = new HashMap<>();

        void recordAttempt(HtmlFetcher.Strategy s, boolean wasBlocked) {
            attempts.merge(s, 1, Integer::sum);
            if (wasBlocked) {
                blocked.merge(s, 1, Integer::sum);
            }
        }

        void recordOgValid(HtmlFetcher.Strategy s) {
            ogValid.merge(s, 1, Integer::sum);
        }

        void recordExtraction(String pattern) {
            patternMatches.merge(pattern, 1, Integer::sum);
        }

        int totalExtractions() {
            return patternMatches.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
