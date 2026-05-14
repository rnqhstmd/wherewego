package com.wherewego.config.env;

import com.wherewego.WherewegoApiApplication;
import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class EnvBindingTest {

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
            "--mapbox.token=test-mapbox-token",
            "--google.places.api-key=test-google-key"
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

    private static void runApplication(String[] args) {
        new SpringApplicationBuilder(WherewegoApiApplication.class)
                .sources(PostgresTestContainersConfig.class)
                .run(args)
                .close();
    }

    private static String[] withOverride(String override) {
        String[] result = new String[BASE_VALID_ARGS.length + 1];
        System.arraycopy(BASE_VALID_ARGS, 0, result, 0, BASE_VALID_ARGS.length);
        result[BASE_VALID_ARGS.length] = override;
        return result;
    }
}
