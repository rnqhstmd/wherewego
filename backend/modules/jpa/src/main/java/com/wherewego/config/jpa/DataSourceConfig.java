package com.wherewego.config.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
class DataSourceConfig {
    @Bean
    @ConfigurationProperties(prefix = "datasource.postgres-jpa.main")
    HikariConfig postgresMainHikariConfig() {
        return new HikariConfig();
    }

    @Primary
    @Bean
    HikariDataSource postgresMainDataSource(@Qualifier("postgresMainHikariConfig") HikariConfig hikariConfig) {
        return new HikariDataSource(hikariConfig);
    }
}
