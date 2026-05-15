package com.wherewego.testcontainers;

import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Configuration
public class PostgresTestContainersConfig {

    private static final PostgreSQLContainer<?> postgresContainer;

    static {
        postgresContainer = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("wherewego")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false);
        postgresContainer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(postgresContainer::stop));

        String postgresJdbcUrl = String.format(
            "jdbc:postgresql://%s:%d/%s",
            postgresContainer.getHost(),
            postgresContainer.getFirstMappedPort(),
            postgresContainer.getDatabaseName()
        );

        System.setProperty("datasource.postgres-jpa.main.jdbc-url", postgresJdbcUrl);
        System.setProperty("datasource.postgres-jpa.main.username", postgresContainer.getUsername());
        System.setProperty("datasource.postgres-jpa.main.password", postgresContainer.getPassword());
    }

    /**
     * 호출 시 클래스가 actually initialize 되어 static block을 트리거한다.
     * Java spec: {@code Class<T>.class} literal은 초기화를 트리거하지 않으므로,
     * Spring Boot이 DataSource 빈을 만들기 전 testcontainer가 확실히 시작되도록 호출 지점을 명시한다.
     */
    public static void ensureStarted() {
        if (!postgresContainer.isRunning()) {
            throw new IllegalStateException("PostgreSQL testcontainer failed to start");
        }
    }
}
