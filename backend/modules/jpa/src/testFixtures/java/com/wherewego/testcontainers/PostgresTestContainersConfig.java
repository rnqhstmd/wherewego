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
            .withPassword("test");
        postgresContainer.start();

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
}
