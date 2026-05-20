package com.wherewego.config.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 요청 본문을 byte[]로 한 번 캐싱하여 여러 번 읽을 수 있게 하는 wrapper.
 *
 * <p>Phase 2.6 PR-B B-3 (M3): {@link ChatbotRateLimitFilter}에서 본문 일부(botUserKey)를
 * 미리 파싱한 뒤 후속 Filter chain의 {@code @RequestBody} 바인딩이 동일 바이트를 다시
 * 읽을 수 있도록 보장한다.</p>
 *
 * <p>본문 크기 상한 {@value #MAX_BODY_BYTES} 바이트를 초과하면 {@link IOException}을
 * 던져 메모리 폭증을 방지한다 (Sec HIGH 대응).</p>
 */
public class BufferedRequestWrapper extends HttpServletRequestWrapper {

    private static final int MAX_BODY_BYTES = 64 * 1024; // 64KB

    private final byte[] cachedBody;

    public BufferedRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        InputStream is = request.getInputStream();
        byte[] limited = is.readNBytes(MAX_BODY_BYTES + 1);
        if (limited.length > MAX_BODY_BYTES) {
            throw new IOException("body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        this.cachedBody = limited;
    }

    public byte[] getCachedBody() {
        return cachedBody.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // no-op — 동기 Servlet 전용 (Phase 2.6 B-3)
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        String encoding = getCharacterEncoding() != null
                ? getCharacterEncoding()
                : StandardCharsets.UTF_8.name();
        return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
    }
}
