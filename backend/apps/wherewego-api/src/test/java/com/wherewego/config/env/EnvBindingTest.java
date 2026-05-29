package com.wherewego.config.env;

import com.wherewego.WherewegoApiApplication;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class EnvBindingTest {

    @BeforeAll
    static void ensureContainerStartedBeforeAnyContextLoad() {
        // Class.class literal은 클래스 초기화를 트리거하지 않으므로, 첫 ApplicationContext 시작 전에
        // testcontainer를 시작하여 DataSource 빈 생성 시점에 jdbc-url 시스템 프로퍼티가 적용되도록 한다.
        PostgresTestContainersConfig.ensureStarted();
    }

    private static final String[] BASE_VALID_ARGS = new String[] {
            "--spring.profiles.active=test",
            "--server.port=0",
            "--jwt.secret=12345678901234567890123456789012",
            "--jwt.access-ttl-seconds=3600",
            "--jwt.refresh-ttl-seconds=1209600",
            "--kakao.local-api-key=test-local",
            "--kakao.oauth.client-id=client",
            "--kakao.oauth.client-secret=secret",
            "--kakao.oauth.redirect-uri=https://example.com/callback",
            "--kakao.local.base-url=https://dapi.kakao.com",
            "--kakao.local.timeout-ms=1500",
            "--kakao.skill.secret=test-kakao-skill-secret",
            "--kakao.callback.timeout-ms=3000",
            "--place.instagram.scraping-enabled=true",
            "--place.search.sync-deadline-ms=4500",
            "--place.search.kakao-local-size=5",
            "--place.search.google-sync-threshold-ms=1700",
            "--place.scraper.instagram.timeout-ms=8000",
            "--place.scraper.gemini.enabled=false",
            "--place.scraper.gemini.api-key=",
            "--place.scraper.gemini.timeout-ms=3000",
            "--place.scraper.gemini.daily-quota-per-user=50",
            "--bot.link-code.ttl-minutes=10",
            "--bot.link-code.max-generation-retries=5",
            "--mapbox.token=test-mapbox-token",
            "--wherewego.s3.bucket=test-bucket",
            "--wherewego.s3.region=ap-northeast-2",
            "--wherewego.s3.public-base-url=https://test-bucket.s3.ap-northeast-2.amazonaws.com",
            "--google.places.api-key=test-google-key",
            "--google.places.base-url=https://places.googleapis.com",
            "--google.places.timeout-ms=1500",
            "--web-security.cookie.secure=false",
            "--web-security.cookie.domain=",
            "--web-security.cookie.same-site=Lax",
            "--web-security.cors.allowed-origins=http://localhost:3000"
    };

    @Test
    void missingJwtSecretFailsStartup() {
        String[] args = withOverride("--jwt.secret=");

        assertThatThrownBy(() -> runApplication(args))
                .isNotNull();
    }

    @Test
    void shortJwtSecretFailsValidation() {
        String[] args = withOverride("--jwt.secret=short");

        assertThatThrownBy(() -> runApplication(args))
                .isNotNull();
    }

    @Test
    void missingKakaoOAuthClientIdFailsStartup() {
        String[] args = withOverride("--kakao.oauth.client-id=");

        assertThatThrownBy(() -> runApplication(args))
                .isNotNull();
    }

    @Test
    void validEnvBindsSuccessfully() {
        assertThatCode(() -> runApplication(BASE_VALID_ARGS))
                .doesNotThrowAnyException();
    }

    @Test
    void emptyCookieDomainBindsAsEmptyStringNotNull() {
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(WherewegoApiApplication.class)
                    .sources(PostgresTestContainersConfig.class)
                    .run(BASE_VALID_ARGS);
            WebSecurityProperties props = context.getBean(WebSecurityProperties.class);
            assertThat(props.cookie().domain()).isNotNull().isEmpty();
            assertThat(props.cookie().secure()).isFalse();
            assertThat(props.cookie().sameSite()).isEqualTo("Lax");
            assertThat(props.cors().allowedOrigins()).containsExactly("http://localhost:3000");
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static void runApplication(String[] args) {
        // Class<T>.class literal은 클래스 초기화를 트리거하지 않으므로 static block의 testcontainer 시작이
        // DataSource 빈 생성보다 늦어질 수 있다. 명시 호출로 컨테이너 시작과 System property 주입을 보장한다.
        PostgresTestContainersConfig.ensureStarted();
        new SpringApplicationBuilder(WherewegoApiApplication.class)
                .sources(PostgresTestContainersConfig.class)
                .run(args)
                .close();
    }

    private static String[] withOverride(String override) {
        // Spring의 SimpleCommandLinePropertySource는 동일 key의 다중 값을 쉼표로 결합하여
        // 단일 문자열로 노출한다. BASE의 정상값과 override의 빈/잘못된 값이 합쳐지면 validation을
        // 우회하므로, 기존 동일 key 인자를 제거하고 override만 적용해야 한다.
        String overrideKey = override.contains("=") ? override.substring(0, override.indexOf('=') + 1) : override;
        java.util.List<String> filtered = new java.util.ArrayList<>();
        for (String arg : BASE_VALID_ARGS) {
            if (!arg.startsWith(overrideKey)) {
                filtered.add(arg);
            }
        }
        filtered.add(override);
        return filtered.toArray(new String[0]);
    }
}
