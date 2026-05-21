package com.wherewego.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NotificationSseRegistryTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long UNKNOWN_USER_ID = 99L;

    private NotificationCreatedEvent sampleEvent(Long receiverId) {
        return new NotificationCreatedEvent(
                receiverId,
                100L,
                NotificationType.MANUAL_PIN,
                2L,
                "민지",
                "성수",
                1,
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    /**
     * 주의: Spring SseEmitter#complete() / completeWithError() 는 HTTP 응답 handler 가
     * 연결된 후에만 onCompletion/onError 콜백을 발화한다 (ResponseBodyEmitter 내부 가드).
     * 단위 테스트에서는 handler 가 없으므로 emitter.complete() 만으로는 registry 제거를
     * 검증할 수 없다. 따라서 removeEmitter 를 직접 호출하여 동일 효과(콜백 실행 결과)를
     * 검증한다. 실제 콜백 발화 경로는 통합 테스트(NotificationV1ControllerIntegrationTest) 가 커버한다.
     */
    @DisplayName("removeEmitter 직접 호출은 (콜백 실행 효과와 동치),")
    @Nested
    class EmitterRemoval {

        @DisplayName("emitter 1개 제거 시 registry 에서 사라진다. (AC-10)")
        @Test
        void removeEmitter_removesFromRegistry() {
            // arrange
            NotificationSseRegistry registry = new NotificationSseRegistry();
            SseEmitter emitter = registry.register(USER_ID);
            assertThat(registry.activeEmitterCount(USER_ID)).isEqualTo(1);

            // act: 운영에서는 onCompletion 콜백이 동일 코드를 호출한다.
            registry.removeEmitter(USER_ID, emitter);

            // assert
            assertThat(registry.activeEmitterCount(USER_ID)).isEqualTo(0);
            assertThat(registry.totalEmitterCount()).isEqualTo(0);
        }

        @DisplayName("error 경로에서도 동일하게 제거된다. (AC-10)")
        @Test
        void removeEmitter_errorPath_removesFromRegistry() {
            // arrange
            NotificationSseRegistry registry = new NotificationSseRegistry();
            SseEmitter emitter = registry.register(USER_ID);

            // act: 운영에서는 onError 콜백이 동일 코드를 호출한다.
            registry.removeEmitter(USER_ID, emitter);

            // assert
            assertThat(registry.activeEmitterCount(USER_ID)).isEqualTo(0);
        }

        @DisplayName("emitter 가 모두 제거되면 내부 map 의 userId 키도 정리된다.")
        @Test
        void allEmittersRemoved_keyEvicted() {
            // arrange
            NotificationSseRegistry registry = new NotificationSseRegistry();
            SseEmitter emitter = registry.register(USER_ID);

            // act
            registry.removeEmitter(USER_ID, emitter);

            // assert
            assertThat(registry.activeEmitterCount(USER_ID)).isEqualTo(0);
            assertThat(registry.totalEmitterCount()).isEqualTo(0);
        }
    }

    @DisplayName("register 는,")
    @Nested
    class Register {

        @DisplayName("호출 직후 activeEmitterCount 가 1이 된다.")
        @Test
        void register_increasesActiveCount() {
            // arrange
            NotificationSseRegistry registry = new NotificationSseRegistry();

            // act
            registry.register(USER_ID);

            // assert
            assertThat(registry.activeEmitterCount(USER_ID)).isEqualTo(1);
            assertThat(registry.totalEmitterCount()).isEqualTo(1);
        }

        @DisplayName("동일 userId 로 두 번 호출하면 activeEmitterCount 가 2가 된다. (다중 탭, FR-9)")
        @Test
        void register_twice_sameUser_increasesCountToTwo() {
            // arrange
            NotificationSseRegistry registry = new NotificationSseRegistry();

            // act
            registry.register(USER_ID);
            registry.register(USER_ID);

            // assert
            assertThat(registry.activeEmitterCount(USER_ID)).isEqualTo(2);
            assertThat(registry.totalEmitterCount()).isEqualTo(2);
        }
    }

    @DisplayName("동일 userId 다중 register 는,")
    @Nested
    class MultipleEmitters {

        @DisplayName("모두 활성으로 유지되고 push 후에도 그대로 남는다. (FR-9)")
        @Test
        void multipleEmitters_samePush() {
            // arrange
            NotificationSseRegistry registry = new NotificationSseRegistry();
            registry.register(USER_ID);
            registry.register(USER_ID);
            assertThat(registry.activeEmitterCount(USER_ID)).isEqualTo(2);

            // act
            registry.push(USER_ID, sampleEvent(USER_ID));

            // assert: push 자체는 IOException 이 없으면 emitter 를 유지한다
            assertThat(registry.activeEmitterCount(USER_ID)).isEqualTo(2);
        }
    }

    @DisplayName("broadcastHeartbeat 는,")
    @Nested
    class Heartbeat {

        @DisplayName("모든 emitter 에 comment 를 발사한 뒤에도 활성 상태를 유지한다.")
        @Test
        void broadcastHeartbeat_keepsActive() {
            // arrange
            NotificationSseRegistry registry = new NotificationSseRegistry();
            registry.register(USER_ID);
            registry.register(OTHER_USER_ID);

            // act
            registry.broadcastHeartbeat();

            // assert
            assertThat(registry.totalEmitterCount()).isEqualTo(2);
        }
    }

    @DisplayName("push 는,")
    @Nested
    class Push {

        @DisplayName("등록된 emitter 가 없는 userId 면 예외 없이 조용히 종료된다.")
        @Test
        void push_noEmitter_silent() {
            // arrange
            NotificationSseRegistry registry = new NotificationSseRegistry();

            // act & assert
            assertThatCode(() -> registry.push(UNKNOWN_USER_ID, sampleEvent(UNKNOWN_USER_ID)))
                    .doesNotThrowAnyException();
            assertThat(registry.totalEmitterCount()).isEqualTo(0);
        }
    }
}
