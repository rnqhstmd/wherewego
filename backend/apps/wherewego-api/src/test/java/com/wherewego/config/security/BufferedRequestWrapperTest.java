package com.wherewego.config.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BufferedRequestWrapper} 단위 테스트.
 *
 * <p>Phase 2.6 PR-B B-3: 본문 byte[] 캐싱 및 다회 재읽기 보장 검증.</p>
 */
class BufferedRequestWrapperTest {

    @DisplayName("본문을 캐싱할 때,")
    @Nested
    class CacheBody {

        @DisplayName("생성자가 요청 본문을 byte[] 로 캐싱하면, getCachedBody 가 동일 바이트를 반환한다.")
        @Test
        void constructor_cachesRequestBody() throws IOException {
            // arrange
            byte[] body = "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.setContent(body);

            // act
            BufferedRequestWrapper wrapper = new BufferedRequestWrapper(request);

            // assert
            assertThat(wrapper.getCachedBody()).isEqualTo(body);
        }

        @DisplayName("body 가 빈 경우에도 정상 처리되어 cachedBody 는 길이 0 byte[] 가 된다.")
        @Test
        void constructor_emptyBody_returnsEmptyArray() throws IOException {
            // arrange
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.setContent(new byte[0]);

            // act
            BufferedRequestWrapper wrapper = new BufferedRequestWrapper(request);

            // assert
            assertThat(wrapper.getCachedBody()).isNotNull();
            assertThat(wrapper.getCachedBody()).isEmpty();
        }
    }

    @DisplayName("InputStream 을 재읽을 때,")
    @Nested
    class GetInputStream {

        @DisplayName("getInputStream 을 두 번 호출해도 각각 동일 body 를 처음부터 읽을 수 있다.")
        @Test
        void getInputStream_calledTwice_independentReads() throws IOException {
            // arrange
            byte[] body = "{\"userRequest\":{\"user\":{\"id\":\"K1\"}}}".getBytes(StandardCharsets.UTF_8);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.setContent(body);
            BufferedRequestWrapper wrapper = new BufferedRequestWrapper(request);

            // act
            byte[] first = wrapper.getInputStream().readAllBytes();
            byte[] second = wrapper.getInputStream().readAllBytes();

            // assert
            assertThat(first).isEqualTo(body);
            assertThat(second).isEqualTo(body);
        }

        @DisplayName("ServletInputStream 의 isReady 는 항상 true, isFinished 는 모든 바이트 소비 시 true 가 된다.")
        @Test
        void servletInputStream_isFinishedAndIsReady() throws IOException {
            // arrange
            byte[] body = "ab".getBytes(StandardCharsets.UTF_8);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.setContent(body);
            BufferedRequestWrapper wrapper = new BufferedRequestWrapper(request);

            // act
            ServletInputStream in = wrapper.getInputStream();

            // assert
            assertThat(in.isReady()).isTrue();
            assertThat(in.isFinished()).isFalse();
            assertThat(in.read()).isEqualTo((int) 'a');
            assertThat(in.isFinished()).isFalse();
            assertThat(in.read()).isEqualTo((int) 'b');
            assertThat(in.isFinished()).isTrue();
        }
    }

    @DisplayName("본문 크기 상한을 적용할 때,")
    @Nested
    class BodySizeLimit {

        private static final int MAX_BODY_BYTES = 64 * 1024;

        @DisplayName("정확히 64KB 본문은 정상 캐싱된다.")
        @Test
        void constructor_exactly64KB_succeeds() throws IOException {
            // arrange
            byte[] body = new byte[MAX_BODY_BYTES];
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.setContent(body);

            // act
            BufferedRequestWrapper wrapper = new BufferedRequestWrapper(request);

            // assert
            assertThat(wrapper.getCachedBody()).hasSize(MAX_BODY_BYTES);
        }

        @DisplayName("64KB+1 byte 본문은 IOException 을 던진다 (Sec HIGH 가드).")
        @Test
        void constructor_exceeding64KB_throwsIOException() {
            // arrange
            byte[] body = new byte[MAX_BODY_BYTES + 1];
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.setContent(body);

            // act + assert
            assertThatThrownBy(() -> new BufferedRequestWrapper(request))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("body exceeds");
        }
    }

    @DisplayName("setReadListener 를 호출할 때,")
    @Nested
    class SetReadListenerNoOp {

        @DisplayName("동기 Servlet 전용이므로 no-op 로 처리되어 예외를 던지지 않는다.")
        @Test
        void setReadListener_isNoOp() throws IOException {
            // arrange
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.setContent("{}".getBytes(StandardCharsets.UTF_8));
            BufferedRequestWrapper wrapper = new BufferedRequestWrapper(request);
            ServletInputStream in = wrapper.getInputStream();

            // act + assert
            assertThatCode(() -> in.setReadListener(new ReadListener() {
                @Override
                public void onDataAvailable() {
                }

                @Override
                public void onAllDataRead() {
                }

                @Override
                public void onError(Throwable t) {
                }
            })).doesNotThrowAnyException();
        }
    }

    @DisplayName("Reader 로 읽을 때,")
    @Nested
    class GetReader {

        @DisplayName("getReader 는 UTF-8 인코딩으로 본문을 문자열로 읽을 수 있다.")
        @Test
        void getReader_readsBodyAsUtf8() throws IOException {
            // arrange
            String text = "한글 본문 OK";
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chatbot/webhook");
            request.setContent(text.getBytes(StandardCharsets.UTF_8));
            BufferedRequestWrapper wrapper = new BufferedRequestWrapper(request);

            // act
            BufferedReader reader = wrapper.getReader();
            String result = reader.lines().reduce("", (a, b) -> a + b);

            // assert
            assertThat(result).isEqualTo(text);
        }
    }
}
