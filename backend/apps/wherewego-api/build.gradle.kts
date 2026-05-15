dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // in-memory cache (ADR-0002: Redis -> Caffeine 전환)
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // html scraping (Instagram OG meta)
    implementation("org.jsoup:jsoup:1.17.2")

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // flyway
    implementation("org.flywaydb:flyway-database-postgresql")

    // postgresql
    runtimeOnly("org.postgresql:postgresql")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // security
    implementation("org.springframework.boot:spring-boot-starter-security")

    // jwt (jjwt 0.12.x)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))

    // security & oauth mock (test only)
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.wiremock:wiremock-standalone:3.9.1")
}

// bootRun working dir을 backend root로 고정.
// application.yml의 `spring.config.import: optional:file:.env[.properties]`가 상대 경로이므로
// 기본 working dir(module 디렉토리)에서는 backend/.env를 찾지 못해 환경변수 placeholder가 미해석된다.
tasks.bootRun {
    workingDir = rootProject.projectDir
}
