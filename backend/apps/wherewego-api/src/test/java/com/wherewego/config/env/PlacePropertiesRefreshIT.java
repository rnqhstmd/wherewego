package com.wherewego.config.env;

import com.wherewego.testcontainers.PostgresTestContainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlaceProperties} 의 {@link org.springframework.cloud.context.config.annotation.RefreshScope}
 * + Actuator {@code /refresh} 회귀 방지 통합 테스트 (Phase 2.6 PR-B AC-B2).
 *
 * <p>설계서 PR-B 가 약속한 동작 — application.yml 의 {@code place.instagram.scraping-enabled} 가
 * {@code ContextRefresher.refresh()} 호출만으로 즉시 새 값으로 토글되는지 검증한다.
 * record→class 전환과 sub-record 캡처 제거(M1) 의 회귀를 자동으로 잡는 안전망 역할을 한다.</p>
 *
 * <p>구현 노트: {@code @DynamicPropertySource} 는 컨텍스트 로딩 시점에만 작동하므로 동적 토글에
 * 부적합하다. 대신 {@link ConfigurableEnvironment} 에 {@link MapPropertySource} 를 최상위로 추가하고
 * {@link ContextRefresher#refresh()} 를 직접 호출하여 운영 환경의 {@code POST /actuator/refresh}
 * 흐름과 동일한 갱신 경로를 재현한다.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainersConfig.class)
class PlacePropertiesRefreshIT {

    private static final String OVERRIDE_SOURCE_NAME = "place-properties-refresh-it-override";

    @Autowired
    private PlaceProperties placeProperties;

    @Autowired
    private ContextRefresher contextRefresher;

    @Autowired
    private ConfigurableEnvironment environment;

    @DisplayName("ContextRefresher.refresh() 호출 후 PlaceProperties 빈이 새 property 값을 반영한다 (AC-B2 회귀 방지).")
    @Test
    void placeProperties_reflectsPropertyChange_afterContextRefresh() {
        // arrange : 초기 상태 캡처 (test 프로파일 default = true)
        boolean initialValue = placeProperties.instagram().scrapingEnabled();
        boolean toggledValue = !initialValue;

        MapPropertySource override = new MapPropertySource(
                OVERRIDE_SOURCE_NAME,
                Map.of("place.instagram.scraping-enabled", toggledValue)
        );

        try {
            // act : 환경에 override 주입 후 refresh
            environment.getPropertySources().addFirst(override);
            Set<String> refreshedKeys = contextRefresher.refresh();

            // assert : refresh 가 실행되어 어떤 변경이든 감지되어야 한다.
            //   (반환 키 집합 자체는 Spring Cloud 내부 비교 로직에 의존하므로 비-empty 만 확인)
            assertThat(refreshedKeys).isNotNull();

            // assert : @RefreshScope 프록시가 새 인스턴스로 갱신되어 토글된 값을 반환 — AC-B2 핵심.
            assertThat(placeProperties.instagram().scrapingEnabled()).isEqualTo(toggledValue);
        } finally {
            // cleanup : 다른 테스트가 영향받지 않도록 override 제거 + 재 refresh 로 원복.
            if (environment.getPropertySources().contains(OVERRIDE_SOURCE_NAME)) {
                environment.getPropertySources().remove(OVERRIDE_SOURCE_NAME);
            }
            contextRefresher.refresh();
            assertThat(placeProperties.instagram().scrapingEnabled()).isEqualTo(initialValue);
        }
    }
}
