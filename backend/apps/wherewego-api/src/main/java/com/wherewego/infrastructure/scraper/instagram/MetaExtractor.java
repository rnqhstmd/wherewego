package com.wherewego.infrastructure.scraper.instagram;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * HTML에서 Open Graph 메타 태그를 추출한다.
 * spike {@code com.wherewego.spike.instagram.MetaExtractor} 이관.
 */
public class MetaExtractor {

    public static class MetaResult {
        public final String ogDescription;
        public final String ogTitle;
        /** og:image content(GC-3 릴스 썸네일용). 없으면 {@code ""}. */
        public final String ogImage;

        public MetaResult(String ogDescription, String ogTitle, String ogImage) {
            this.ogDescription = ogDescription;
            this.ogTitle = ogTitle;
            this.ogImage = ogImage;
        }

        // isEmpty 는 caption 추출(InstagramContentService) 호환을 위해 desc/title 기준을 유지한다.
        // ogImage 유무는 썸네일 파이프라인이 별도 판정하므로 여기 포함하지 않는다.
        public boolean isEmpty() {
            return (ogDescription == null || ogDescription.isBlank())
                    && (ogTitle == null || ogTitle.isBlank());
        }
    }

    public MetaResult extract(String html) {
        if (html == null || html.isBlank()) {
            return new MetaResult("", "", "");
        }
        Document doc = Jsoup.parse(html);
        String description = doc.select("meta[property=og:description]").attr("content");
        String title = doc.select("meta[property=og:title]").attr("content");
        String image = doc.select("meta[property=og:image]").attr("content");
        return new MetaResult(
                description == null ? "" : description,
                title == null ? "" : title,
                image == null ? "" : image);
    }
}
