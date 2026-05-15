package com.wherewego.spike.instagram;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * HTML에서 Open Graph 메타 태그를 추출한다.
 * 설계서 §3.4.1.
 */
public class MetaExtractor {

    public static class MetaResult {
        public final String ogDescription;
        public final String ogTitle;

        public MetaResult(String ogDescription, String ogTitle) {
            this.ogDescription = ogDescription;
            this.ogTitle = ogTitle;
        }

        public boolean isEmpty() {
            return (ogDescription == null || ogDescription.isBlank())
                    && (ogTitle == null || ogTitle.isBlank());
        }
    }

    public MetaResult extract(String html) {
        if (html == null || html.isBlank()) {
            return new MetaResult("", "");
        }
        Document doc = Jsoup.parse(html);
        String description = doc.select("meta[property=og:description]").attr("content");
        String title = doc.select("meta[property=og:title]").attr("content");
        return new MetaResult(description == null ? "" : description, title == null ? "" : title);
    }
}
