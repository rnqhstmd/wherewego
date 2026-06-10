package com.wherewego.infrastructure.scraper.instagram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GC-3(FR-GC3-2/M1): {@link MetaExtractor} 의 og:image 추출 단위 테스트.
 * 외부 네트워크 의존 없이 인라인 HTML 문자열만으로 파싱을 검증한다.
 */
class MetaExtractorTest {

    private final MetaExtractor metaExtractor = new MetaExtractor();

    @DisplayName("extract - og:image 메타 태그가 있으면 content 를 ogImage 로 추출한다.")
    @Test
    void extract_picksOgImage() {
        String html = "<html><head>"
                + "<meta property=\"og:title\" content=\"릴스 제목\">"
                + "<meta property=\"og:description\" content=\"성수 카페 캡션\">"
                + "<meta property=\"og:image\" content=\"https://scontent.cdninstagram.com/v/thumb.jpg\">"
                + "</head><body></body></html>";

        MetaExtractor.MetaResult result = metaExtractor.extract(html);

        assertThat(result.ogImage).isEqualTo("https://scontent.cdninstagram.com/v/thumb.jpg");
        assertThat(result.ogTitle).isEqualTo("릴스 제목");
        assertThat(result.ogDescription).isEqualTo("성수 카페 캡션");
    }

    @DisplayName("extract - og:image 가 없으면 ogImage 는 빈 문자열이고 isEmpty 는 desc/title 기준을 유지한다.")
    @Test
    void extract_noOgImage_returnsEmptyString() {
        String html = "<html><head>"
                + "<meta property=\"og:title\" content=\"제목만\">"
                + "</head><body></body></html>";

        MetaExtractor.MetaResult result = metaExtractor.extract(html);

        assertThat(result.ogImage).isEqualTo("");
        // isEmpty 는 og:image 와 무관 — title 이 있으므로 false(InstagramContentService 호환).
        assertThat(result.isEmpty()).isFalse();
    }

    @DisplayName("extract - null/blank HTML 은 빈 MetaResult(ogImage 포함)를 반환한다.")
    @Test
    void extract_blankHtml_returnsEmptyResult() {
        MetaExtractor.MetaResult result = metaExtractor.extract("  ");

        assertThat(result.ogImage).isEqualTo("");
        assertThat(result.isEmpty()).isTrue();
    }
}
