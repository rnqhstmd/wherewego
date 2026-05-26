package com.wherewego;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;
import java.util.TimeZone;

@EnableRetry
@ConfigurationPropertiesScan
@SpringBootApplication
public class WherewegoApiApplication {

    @PostConstruct
    public void started() {
        // set timezone
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(WherewegoApiApplication.class, args);
    }
}
