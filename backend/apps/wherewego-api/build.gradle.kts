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

    // AWS S3 (Phase 13 추억핀 사진 — BOM 으로 버전 관리, 개별 artifact 는 버전 생략)
    implementation(platform("software.amazon.awssdk:bom:2.31.78"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:apache-client") // S3Config ApacheHttpClient 타임아웃용

    // image thumbnail (Phase 13 — 장변 256px WebP 인코딩, 번들 libwebp 네이티브)
    implementation("com.sksamuel.scrimage:scrimage-core:4.3.0")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.3.0")

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

    // spring cloud context (@RefreshScope + /actuator/refresh)
    implementation("org.springframework.cloud:spring-cloud-starter")

    // rate limiting (Bucket4j 토큰 버킷, Phase 2.6 PR-B B-3)
    implementation("com.bucket4j:bucket4j-core:${project.properties["bucket4jVersion"]}")

    // retry (Neon cold start 대응 — UserLoginPersistence @Retryable)
    implementation("org.springframework.retry:spring-retry")

    // jwt (jjwt 0.12.x) — 대칭키 우리 JWT 발급/검증 전용
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Apple identityToken JWKS 검증 전용 (RemoteJWKSet 캐싱·키 로테이션·RS256 내장).
    // Spring Boot 3.4 BOM 은 nimbus-jose-jwt 를 직접 관리하지 않으므로(spring-security-oauth2-jose 미사용)
    // 버전을 명시한다 (9.x 최신 안정).
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")

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
