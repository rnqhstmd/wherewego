package com.wherewego.spike.instagram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 인스타그램 릴스 페이지 HTML을 3단계 헤더 전략으로 시도한다.
 * 설계서 §3.4.4 우회 시도 3단계 (AC-18 누적 차단율 측정).
 */
public class HtmlFetcher {

    public enum Strategy {
        NO_UA,
        CHROME_UA,
        FULL_HEADERS
    }

    public static class FetchResult {
        public final int statusCode;
        public final String body;
        public final boolean blocked;
        public final long elapsedMs;
        public final Strategy strategy;

        public FetchResult(int statusCode, String body, boolean blocked, long elapsedMs, Strategy strategy) {
            this.statusCode = statusCode;
            this.body = body;
            this.blocked = blocked;
            this.elapsedMs = elapsedMs;
            this.strategy = strategy;
        }
    }

    private static final String CHROME_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final HttpClient client;

    public HtmlFetcher() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public FetchResult fetch(String url, Strategy strategy) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET();

            applyStrategy(builder, strategy);

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            String body = response.body() == null ? "" : response.body();
            boolean blocked = response.statusCode() != 200 || !body.contains("og:description");

            return new FetchResult(response.statusCode(), body, blocked, elapsed, strategy);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new FetchResult(-1, "", true, elapsed, strategy);
        }
    }

    private void applyStrategy(HttpRequest.Builder builder, Strategy strategy) {
        switch (strategy) {
            case NO_UA:
                // 헤더 없이 요청
                break;
            case CHROME_UA:
                builder.header("User-Agent", CHROME_UA);
                break;
            case FULL_HEADERS:
                builder.header("User-Agent", CHROME_UA);
                builder.header("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
                builder.header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
                builder.header("Accept-Encoding", "identity");
                builder.header("Cache-Control", "no-cache");
                builder.header("Pragma", "no-cache");
                builder.header("Referer", "https://www.instagram.com/");
                builder.header("Sec-Fetch-Dest", "document");
                builder.header("Sec-Fetch-Mode", "navigate");
                builder.header("Sec-Fetch-Site", "none");
                builder.header("Sec-Fetch-User", "?1");
                builder.header("Upgrade-Insecure-Requests", "1");
                break;
        }
    }
}
