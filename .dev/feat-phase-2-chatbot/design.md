# Phase 2 기술 설계서 (최종)

## 1. 개요

카카오톡 챗봇(i 오픈빌더 Skill 서버)을 통해 (a) 6자리 연동코드로 카카오 사용자와 wherewego 계정을 연결하고 (b) 인스타그램 게시물 URL → 카카오 로컬 검색 → Pin 자동 등록 → 2초 룰 메모를 지원하는 동기 처리 파이프라인을 구현한다. 카카오 Skill 5초 SLA를 안정적으로 충족하기 위해 모든 처리는 단일 스레드에서 수행되며, 데드라인 기반 컷오프로 외부 호출 지연을 흡수한다.

**설계 규모: 대형** — 6개 신규 도메인 + 인스타 스크래퍼 이관 + 보안 필터 + 12종 ErrorType 등 30+ 신규 클래스.

## 2. 아키텍처 개요

### 2.1 도메인 분리표

| 도메인 | 패키지 | 책임 |
|---|---|---|
| **bot** | `com.wherewego.domain.bot` | 6자리 연동코드 발급/소비, 카카오 사용자-앱 사용자 매핑 |
| **chatbot** | `com.wherewego.domain.chatbot` | Skill webhook 진입 → 메시지 분류 → 핸들러 위임 → SkillResponse 조립 |
| **place** | `com.wherewego.domain.place` | ContentParser 정의/InstagramParser, 인스타 메타 추출, 카카오 Local 검색, Outcome 산출 |
| **pin** | `com.wherewego.domain.pin` | Pin 엔티티/등록/2초 메모 자동 부착 |
| **group** | `com.wherewego.domain.group` | (Phase 3 선행) GroupMember 조회 — 사용자 최근 활성 그룹 ID 해석 |

### 2.2 동기 처리 흐름

```
[카카오 i 오픈빌더]
      │ POST /api/v1/chatbot/webhook (X-Kakao-Skill-Secret)
      ▼
[KakaoSkillSecretFilter] ── 401 (헤더 불일치)
      │ pass
      ▼
[ChatbotV1Controller]
      │
      ▼
[ChatbotWebhookService.handle(SkillRequest)]   ← t0 = System.currentTimeMillis()
      │
      ▼
[MessageClassifier]  ┌───────────────────────────────────────────┐
      │              │ LINK_CODE     → LinkCodeHandler           │
      │              │ INSTAGRAM_LINK→ InstagramLinkHandler       │
      ├──────────────┤ PLACE_SELECT  → PlaceSelectionHandler      │
      │              │ TEXT_2SEC     → TwoSecondMemoHandler       │
      │              │ UNKNOWN       → UnknownHandler             │
      │              └───────────────────────────────────────────┘
      ▼
[Handler 실행]
      │  ├─ InstagramContentService.extract (deadline check)
      │  ├─ PlaceSearchService.searchByKeyword (deadline check)
      │  ├─ PinService.registerFromInstagram / registerFromSelection
      │  ├─ PinMemoService.attachAutoMemoIfWithinWindow (조건부 UPDATE)
      │  └─ TwoSecondMemoSession (Caffeine)
      ▼
[SkillResponse 빌더]
      │
      ▼
[200 OK + JSON]
```

### 2.3 5초 SLA 데드라인 정책

| 항목 | 값 |
|---|---|
| 측정 기준점 | `ChatbotWebhookService.handle` 진입 시 `t0` 캡처 |
| 전체 데드라인 | `place.search.sync-deadline-ms=4500` (카카오 5000ms - 안전 마진 500ms) |
| 컷오프 위치 | InstagramScraperClient 호출 직전 / KakaoLocalClient 호출 직전 |
| 컷오프 동작 | 스크래핑 단계: 폴백 SimpleText(WARN 로그) / 검색 단계: `Outcome.Empty()` |
| 개별 호출 타임아웃 | `kakao.local.timeout-ms=1500`, `instagram.scraper.timeout-ms=8000` (데드라인이 우선) |

```
remaining = deadlineMs - (currentTimeMillis() - t0)
if (remaining <= 0)  → 컷오프
```

## 3. 도메인 모델 / 엔티티 / Repository

### 3.1 BotLinkCode (BaseEntity 미상속, CHAR(6))

`com.wherewego.domain.bot.BotLinkCode`

```java
@Entity
@Table(name = "bot_link_code")
public class BotLinkCode {
    @Id @GeneratedValue(strategy = IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(columnDefinition = "CHAR(6)", nullable = false) private String code;
    @Enumerated(STRING) @Column(nullable = false) private BotLinkCodeStatus status; // ACTIVE/CONSUMED/EXPIRED
    @Column(name = "issued_at", nullable = false) private Instant issuedAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    // BaseEntity 미상속 — created_at/updated_at 컬럼 없음
}
```

**Repository: `BotLinkCodeRepository` (port) + `BotLinkCodeRepositoryImpl` (adapter) + `BotLinkCodeJpaRepository`**

| 메서드 | 시그니처 | 트랜잭션 |
|---|---|---|
| save | `BotLinkCode save(BotLinkCode entity)` | required |
| findActiveByCode | `Optional<BotLinkCode> findActiveByCode(String code, Instant now)` | readOnly |
| findActiveByUserId | `Optional<BotLinkCode> findActiveByUserId(Long userId, Instant now)` | readOnly |
| expireActiveByUserId | `int expireActiveByUserId(Long userId, Instant now)` | required (조건부 UPDATE) |

DB 제약: `CREATE UNIQUE INDEX ux_bot_link_code_user_active ON bot_link_code(user_id) WHERE status='ACTIVE'` (Partial UNIQUE — V001에 이미 포함).

### 3.2 BotUserMapping (BaseEntity 미상속)

`com.wherewego.domain.bot.BotUserMapping`

```java
@Entity
@Table(name = "bot_user_mapping")
public class BotUserMapping {
    @Id @GeneratedValue(strategy = IDENTITY) private Long id;
    @Column(name = "bot_user_key", nullable = false, unique = true) private String botUserKey;
    @Column(name = "user_id", nullable = false, unique = true) private Long userId;
    @Column(name = "linked_at", nullable = false) private Instant linkedAt;
}
```

**Repository: `BotUserMappingRepository` + Impl + Jpa**

| 메서드 | 시그니처 |
|---|---|
| save | `BotUserMapping save(BotUserMapping entity)` |
| findByBotUserKey | `Optional<BotUserMapping> findByBotUserKey(String botUserKey)` |
| findByUserId | `Optional<BotUserMapping> findByUserId(Long userId)` |

### 3.3 Pin (BaseEntity 상속)

`com.wherewego.domain.pin.Pin`

```java
@Entity
@Table(name = "pin",
       uniqueConstraints = @UniqueConstraint(name = "ux_pin_group_instagram",
                                             columnNames = {"group_id","instagram_url"}))
public class Pin extends BaseEntity {
    @Id @GeneratedValue(strategy = IDENTITY) private Long id;
    @Column(name = "group_id", nullable = false) private Long groupId;
    @Column(name = "owner_user_id", nullable = false) private Long ownerUserId;
    @Column(name = "kakao_place_id", nullable = false) private String kakaoPlaceId;
    @Column(name = "place_name", nullable = false) private String placeName;
    @Column(name = "place_address") private String placeAddress;
    @Column private Double latitude;
    @Column private Double longitude;
    @Column(name = "instagram_url") private String instagramUrl;
    @Column private String memo;
    @Enumerated(STRING) @Column(name = "memo_source") private MemoSource memoSource; // MANUAL/AUTO/null
}
```

**Repository: `PinRepository` + Impl + Jpa**

| 메서드 | 시그니처 | 비고 |
|---|---|---|
| save | `Pin save(Pin entity)` | UNIQUE 위반 시 `DataIntegrityViolationException` |
| findById | `Optional<Pin> findById(Long id)` | |
| updateAutoMemoIfNotManual | `int updateAutoMemoIfNotManual(Long pinId, Long ownerUserId, String memo)` | **조건부 UPDATE**: `WHERE id=? AND owner_user_id=? AND (memo_source IS NULL OR memo_source <> 'MANUAL')` |

### 3.4 GroupMember (Phase 3 선행 read-only)

`com.wherewego.domain.group.GroupMember`

```java
@Entity
@Table(name = "group_member")
public class GroupMember extends BaseEntity {
    @Id @GeneratedValue(strategy = IDENTITY) private Long id;
    @Column(name = "group_id", nullable = false) private Long groupId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Enumerated(STRING) @Column(nullable = false) private GroupMemberRole role;
    @Enumerated(STRING) @Column(nullable = false) private GroupMemberStatus status; // ACTIVE/LEFT
}
```

**Repository (Phase 2에서는 read-only):**

| 메서드 | 시그니처 |
|---|---|
| findLatestActiveByUserId | `Optional<GroupMember> findLatestActiveByUserId(Long userId)` (ORDER BY id DESC) |

### 3.5 트랜잭션 경계

| 메서드 | 트랜잭션 |
|---|---|
| `BotLinkCodeService.issueCode` | `@Transactional` (만료 + 신규 INSERT) |
| `BotLinkCodeService.consumeCode` | `@Transactional` |
| `BotUserMappingService.link` | `@Transactional` |
| `PinService.registerFromInstagram` | `@Transactional` |
| `PinService.registerFromSelection` | `@Transactional` |
| `PinMemoService.attachAutoMemoIfWithinWindow` | `@Transactional` (1줄 조건부 UPDATE) |
| `ChatbotWebhookService.handle` | **트랜잭션 미지정** (각 서비스가 자체 경계) |

## 4. 도메인 서비스

### 4.1 BotLinkCodeService — `com.wherewego.domain.bot.BotLinkCodeService`

```java
public BotLinkCodeIssueResult issueCode(Long userId);
public BotLinkCodeConsumeResult consumeCode(String code, Instant now);
```

- **BR-1 충돌 재시도**: `LinkCodeGenerator.generate6Digits()` 호출 후 `findActiveByCode`로 충돌 검사. 최대 5회 재시도. 5회 모두 실패 시 `CoreException(INTERNAL_ERROR)`.
- **BR-2 활성 1개 유지**: `issueCode` 시작 시 `expireActiveByUserId(userId, now)` 호출 → 기존 ACTIVE를 EXPIRED로 전환 후 INSERT.
- **BR-3 TTL**: `expiresAt = issuedAt + 10분` (`bot.link-code.ttl-minutes=10`).
- **consumeCode**: `findActiveByCode(code, now)` 없으면 `BOT_LINK_CODE_INVALID`. `expiresAt < now`면 `BOT_LINK_CODE_EXPIRED`. 성공 시 status=CONSUMED, consumedAt=now.

### 4.2 BotUserMappingService — `com.wherewego.domain.bot.BotUserMappingService`

```java
public BotUserLinkResult link(String code, String botUserKey, Instant now);
public Optional<Long> resolveUserId(String botUserKey);
```

- `link`: ① `findByBotUserKey` 또는 `findByUserId` 중복 시 `BOT_USER_ALREADY_LINKED`. ② `BotLinkCodeService.consumeCode(code, now)`. ③ INSERT.
- `resolveUserId`: `findByBotUserKey(botUserKey).map(BotUserMapping::getUserId)`.

### 4.3 ChatbotWebhookService — `com.wherewego.domain.chatbot.ChatbotWebhookService`

```java
public SkillResponse handle(SkillRequest request);
```

- 진입 즉시 `long t0 = System.currentTimeMillis();` 캡처 → `ChatbotContext(t0, deadlineMs)` 생성.
- `MessageClassifier.classify(request)` → `MessageType` 반환.
- `MessageRouter.route(type)` → 해당 `MessageHandler` 위임.
- 전체를 `try/catch (CoreException | Exception)` 으로 감싸 폴백 `SimpleText` 응답으로 변환 (200 OK + 사용자 안내).

### 4.4 MessageClassifier — `com.wherewego.domain.chatbot.MessageClassifier`

```java
public MessageType classify(SkillRequest req, String botUserKey);
```

5단계 분기 (위에서 아래 순서로 평가):

| 우선순위 | 분류 | 조건 |
|---|---|---|
| 1 | `PLACE_SELECTION` | `req.action().params().get("placeId") != null` |
| 2 | `LINK_CODE` | `userRequest.utterance().trim().matches("^\\d{6}$")` |
| 3 | `INSTAGRAM_LINK` | `userRequest.utterance()` 가 `^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|reels)/[A-Za-z0-9_-]+/?` 매칭 |
| 4 | `TEXT_2SEC_CANDIDATE` | 위 3개 아니고 `twoSecondMemoSession.peek(botUserKey).isPresent()` |
| 5 | `UNKNOWN` | 그 외 |

### 4.5 InstagramContentService — `com.wherewego.domain.place.InstagramContentService`

```java
public Optional<InstagramExtraction> extract(String url, ChatbotContext ctx);
```

- `placeProperties.instagram().scrapingEnabled() == false` → 즉시 `Optional.empty()`.
- 데드라인 컷오프: `ctx.remaining() <= 0` → `CoreException(PLC_INSTAGRAM_SCRAPE_FAILED, "처리가 지연되었어요. 다시 시도해 주세요.")`.
- 그 외 `InstagramScraperClient.fetch(url)` → `MetaExtractor` → `PlaceNameExtractor` → `InstagramExtraction(placeKeyword, captionSnippet)`.

### 4.6 PlaceSearchService — `com.wherewego.domain.place.PlaceSearchService`

```java
public PlaceSearchOutcome searchByKeyword(String keyword, ChatbotContext ctx);
```

sealed 인터페이스 결과:

```java
public sealed interface PlaceSearchOutcome {
    record Single(PlaceSearchHit hit) implements PlaceSearchOutcome {}
    record Multiple(List<PlaceSearchHit> hits) implements PlaceSearchOutcome {} // size <= 5
    record Empty() implements PlaceSearchOutcome {}
}
public record PlaceSearchHit(String kakaoPlaceId, String placeName, String address,
                             Double latitude, Double longitude) {}
```

- 데드라인 초과 / 카카오 API 실패 → `Empty()` 반환 (예외 던지지 않음).
- `place.search.kakao-local-size=5` 로 호출 후 6 이상 trim. size=1 → Single, 2~5 → Multiple, 0 → Empty.

### 4.7 PinService — `com.wherewego.domain.pin.PinService`

```java
@Transactional
public Pin registerFromInstagram(Long userId, Long groupId, PlaceSearchHit hit, String instagramUrl);

@Transactional
public Pin registerFromSelection(Long userId, Long groupId, PlaceSearchHit hit, String instagramUrl);
```

- 두 메서드 모두 동일한 Pin INSERT 로직. `instagramUrl`이 null/blank이면 UNIQUE 검사 우회.
- UNIQUE 충돌 시 `DataIntegrityViolationException` 그대로 throw → 호출자(handler)가 `PLC_DUPLICATE_PIN` 처리.

### 4.8 PinMemoService — `com.wherewego.domain.pin.PinMemoService`

```java
@Transactional
public boolean attachAutoMemoIfWithinWindow(Long pinId, Long ownerUserId, String memo) {
    return pinRepository.updateAutoMemoIfNotManual(pinId, ownerUserId, memo) > 0;
}
```

- **race-safe 1줄 UPDATE**: SELECT 단계 없음. `WHERE id=? AND owner_user_id=? AND (memo_source IS NULL OR memo_source<>'MANUAL')`. 갱신 행 수 0이면 false(이미 MANUAL).
- BR-11: 2초 윈도우 검사는 호출자(`TwoSecondMemoSession.peek`)가 담당. 이 메서드는 윈도우 진입 확정 후 호출.

### 4.9 TwoSecondMemoSession — `com.wherewego.domain.pin.memo.TwoSecondMemoSession`

```java
public void put(String botUserKey, Long pinId);
public Optional<Long> peek(String botUserKey);
public void invalidate(String botUserKey);
```

- Caffeine `twoSecondMemo` 단일 의존. `expireAfterWrite = 2s`, `maximumSize = 10_000`.

### 4.10 GroupMemberService — `com.wherewego.domain.group.GroupMemberService`

```java
public Long findLatestActiveGroupIdByUserId(Long userId);
```

- 활성 그룹 없으면 `CoreException(NOT_FOUND, "사용자의 활성 그룹이 없습니다.")` — chatbot 컨트롤러가 SimpleText로 변환.

## 5. 인프라

### 5.1 InstagramScraperClient — `com.wherewego.infrastructure.scraper.instagram.InstagramScraperClient`

`@Component`. 3-stage spike 흡수 (NO_UA → CHROME_UA → FULL_HEADERS), 단계별 결과 평가:

```java
public Optional<String> fetchHtml(String url, ChatbotContext ctx);
```

- 각 stage 실행 전 `ctx.remaining()` 검사. 0 이하면 즉시 `Optional.empty()`.
- 모든 stage 실패 → `Optional.empty()` (호출자가 `PLC_INSTAGRAM_SCRAPE_FAILED` 발생).
- 내부 의존: 이관된 `HtmlFetcher`, `MetaExtractor`, `PlaceNameExtractor`.

### 5.2 KakaoLocalClient — `com.wherewego.infrastructure.place.kakao.KakaoLocalClient`

`@Component`, Spring `RestClient` 기반.

```java
public List<PlaceSearchHit> searchByKeyword(String keyword, int size, ChatbotContext ctx);
```

- baseUrl: `https://dapi.kakao.com` (KakaoApiProperties.Local).
- Header: `Authorization: KakaoAK ${kakao.local.api-key}`.
- `kakao.local.timeout-ms=1500` → connect/read 타임아웃.
- 호출 직전 `ctx.remaining() <= 0` 체크.
- 5xx / 타임아웃 → `CoreException(PLC_KAKAO_LOCAL_FAILED)`. PlaceSearchService는 이를 catch하여 `Empty()` 변환.

### 5.3 KakaoSkillSecretFilter — `com.wherewego.config.security.KakaoSkillSecretFilter`

```java
@Component
@RequiredArgsConstructor
public class KakaoSkillSecretFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Kakao-Skill-Secret";
    private final KakaoApiProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/v1/chatbot/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        String provided = req.getHeader(HEADER);
        String expected = properties.skill().secret();
        if (provided == null || !MessageDigest.isEqual(provided.getBytes(UTF_8), expected.getBytes(UTF_8))) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"result\":\"FAIL\",\"error\":{\"code\":\"BOT_SKILL_SECRET_INVALID\"}}");
            return;
        }
        chain.doFilter(req, res);
    }
}
```

- 헤더 단순 일치 검사 (HMAC 미사용). 타이밍 공격 완화를 위해 `MessageDigest.isEqual` 사용.
- **SecurityContext 미주입** — 익명 통과만 허용 (downstream에서 별도 인증 없이 botUserKey만 사용).

### 5.4 CacheConfig — `com.wherewego.config.cache.CacheConfig`

```java
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager("twoSecondMemo", "placeSelectionCandidate");
        mgr.registerCustomCache("twoSecondMemo",
            Caffeine.newBuilder().expireAfterWrite(2, SECONDS).maximumSize(10_000).build());
        mgr.registerCustomCache("placeSelectionCandidate",
            Caffeine.newBuilder().expireAfterWrite(10, MINUTES).maximumSize(10_000).build());
        return mgr;
    }
}
```

| 캐시 | key | value | TTL |
|---|---|---|---|
| `twoSecondMemo` | `botUserKey` | `Long pinId` | 2초 |
| `placeSelectionCandidate` | `botUserKey:placeId` | `PlaceSearchHit + instagramUrl` | 10분 |

### 5.5 application.yml 최종 키 목록

```yaml
kakao:
  local-api-key: ${KAKAO_LOCAL_API_KEY}
  oauth:
    client-id: ${KAKAO_CLIENT_ID}
    client-secret: ${KAKAO_CLIENT_SECRET}
    redirect-uri: ${KAKAO_REDIRECT_URI}
  local:
    base-url: https://dapi.kakao.com
    timeout-ms: 1500
  skill:
    secret: ${KAKAO_SKILL_SECRET}

place:
  instagram:
    scraping-enabled: true
  search:
    sync-deadline-ms: 4500
    kakao-local-size: 5
  scraper:
    instagram:
      timeout-ms: 8000

bot:
  link-code:
    ttl-minutes: 10
    max-generation-retries: 5
```

### 5.6 KakaoApiProperties 확장

```java
@Validated
@ConfigurationProperties(prefix = "kakao")
public record KakaoApiProperties(
        @NotBlank String localApiKey,
        @Valid OAuth oauth,
        @Valid Local local,
        @Valid Skill skill
) {
    public record OAuth(@NotBlank String clientId, @NotBlank String clientSecret, @NotBlank String redirectUri) {}
    public record Local(@NotBlank String baseUrl, @Positive int timeoutMs) {}
    public record Skill(@NotBlank String secret) {}
}
```

### 5.7 PlaceProperties — `com.wherewego.config.env.PlaceProperties`

```java
@Validated
@ConfigurationProperties(prefix = "place")
public record PlaceProperties(
        @Valid Instagram instagram,
        @Valid Search search,
        @Valid Scraper scraper
) {
    public record Instagram(boolean scrapingEnabled) {}
    public record Search(@Positive long syncDeadlineMs, @Positive int kakaoLocalSize) {}
    public record Scraper(@Valid InstagramScraper instagram) {}
    public record InstagramScraper(@Positive int timeoutMs) {}
}
```

## 6. REST API

### 6.1 POST /api/v1/bot/link-codes

```java
@RestController
@RequestMapping("/api/v1/bot")
@RequiredArgsConstructor
public class BotV1Controller implements BotV1ApiSpec {
    private final BotLinkCodeService linkCodeService;

    @PostMapping("/link-codes")
    public ApiResponse<BotV1Dto.LinkCodeResponse> issueLinkCode(@AuthUser Long userId) {
        BotLinkCodeIssueResult result = linkCodeService.issueCode(userId);
        return ApiResponse.success(BotV1Dto.LinkCodeResponse.from(result));
    }
}
```

- 인증 필수 (`@AuthUser`).
- Response: `{ "result": "SUCCESS", "data": { "code": "123456", "expiresAt": "2026-05-15T13:10:00Z" } }`.

### 6.2 POST /api/v1/chatbot/webhook (무인증 + KakaoSkillSecretFilter)

```java
@RestController
@RequestMapping("/api/v1/chatbot")
@RequiredArgsConstructor
public class ChatbotV1Controller implements ChatbotV1ApiSpec {
    private final ChatbotWebhookService webhookService;

    @PostMapping("/webhook")
    public ChatbotV1Dto.SkillResponse webhook(@RequestBody ChatbotV1Dto.SkillRequest request) {
        return webhookService.handle(request);
    }
}
```

- **ApiResponse 미사용** — 카카오 i 오픈빌더 SkillResponse 스키마 그대로 반환.
- 컨트롤러 내부에서 `try/catch (Exception)` 으로 폴백 SimpleText 응답. ApiControllerAdvice의 CoreException 핸들러가 이 컨트롤러에는 적용되지 않도록 컨트롤러 레벨 try/catch가 우선.

### 6.3 SecurityConfig 변경 코드 스니펫

```java
@Configuration @EnableWebSecurity @RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final KakaoSkillSecretFilter kakaoSkillSecretFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .cors(c -> c.configurationSource(corsConfigurationSource))
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .exceptionHandling(eh -> eh.authenticationEntryPoint(jwtAuthenticationEntryPoint))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/**",
                    "/api/v1/chatbot/webhook",
                    "/actuator/health",
                    "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(kakaoSkillSecretFilter, JwtAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

### 6.4 DTO 구조

**`com.wherewego.interfaces.api.bot.BotV1Dto`**
```java
public class BotV1Dto {
    public record LinkCodeResponse(String code, Instant expiresAt) {
        public static LinkCodeResponse from(BotLinkCodeIssueResult r) { ... }
    }
    private BotV1Dto() {}
}
```

**`com.wherewego.interfaces.api.chatbot.ChatbotV1Dto`** — i 오픈빌더 스펙 매핑
```java
public class ChatbotV1Dto {
    public record SkillRequest(UserRequest userRequest, Action action) {}
    public record UserRequest(String utterance, User user) {}
    public record User(String id, String type) {}                  // user.id = botUserKey
    public record Action(Map<String, String> params) {}            // params.placeId 등

    public record SkillResponse(String version, Template template) {
        public static SkillResponse simple(String text) { ... }
        public static SkillResponse cards(List<BasicCard> cards) { ... }
    }
    public record Template(List<Map<String, Object>> outputs) {}

    public record SimpleText(String text) {}
    public record BasicCard(String title, String description, Thumbnail thumbnail, List<Button> buttons) {}
    public record ListCard(Header header, List<ListItem> items, List<Button> buttons) {}
    public record Button(String label, String action, String messageText, Map<String, String> extra) {}
    public record Thumbnail(String imageUrl) {}
    public record Header(String title) {}
    public record ListItem(String title, String description, String imageUrl) {}

    private ChatbotV1Dto() {}
}
```

## 7. ContentParser

### 7.1 ContentParser 인터페이스 — `com.wherewego.domain.place.parser.ContentParser`

```java
public interface ContentParser {
    boolean supports(String url);
    Optional<ParsedContent> parse(String url, ChatbotContext ctx);
}
public record ParsedContent(String placeKeyword, String captionSnippet) {}
```

### 7.2 InstagramParser — `com.wherewego.domain.place.parser.InstagramParser`

```java
@Component
@RequiredArgsConstructor
public class InstagramParser implements ContentParser {
    private final InstagramContentService service;
    private static final Pattern URL = Pattern.compile(
        "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|reels)/[A-Za-z0-9_-]+/?.*$");

    public boolean supports(String url) { return URL.matcher(url).matches(); }
    public Optional<ParsedContent> parse(String url, ChatbotContext ctx) {
        return service.extract(url, ctx).map(e -> new ParsedContent(e.placeKeyword(), e.captionSnippet()));
    }
}
```

### 7.3 ContentParserRegistry — `com.wherewego.domain.place.parser.ContentParserRegistry`

```java
@Component
@RequiredArgsConstructor
public class ContentParserRegistry {
    private final List<ContentParser> parsers;   // Spring이 InstagramParser 1개만 주입
    public Optional<ContentParser> resolve(String url) {
        return parsers.stream().filter(p -> p.supports(url)).findFirst();
    }
}
```

- 미지원 URL → `empty()` → InstagramLinkHandler가 폴백 SimpleText 응답.
- TikTok/YouTube는 등록 안 됨 — 미래 확장 시 `@Component` 추가만으로 OCP 만족.

## 8. Skill Webhook 분기 흐름 (5단계 + PLACE_SELECTION)

### 8.1 분류표

| 분류 | 정규식/조건 | 핸들러 | 비고 |
|---|---|---|---|
| `LINK_CODE` | `^\d{6}$` | `LinkCodeHandler` | 미연동/이미 연동 분기 |
| `INSTAGRAM_LINK` | `^https?://(www\.)?(instagram\.com\|instagr\.am)/(p\|reel\|reels)/[A-Za-z0-9_-]+/?` | `InstagramLinkHandler` | 5초 SLA 데드라인 |
| `PLACE_SELECTION` | `action.params.placeId != null` (최우선) | `PlaceSelectionHandler` | 두 번째 webhook |
| `TEXT_2SEC_CANDIDATE` | 위 셋 아니고 `twoSecondMemo.peek(botUserKey).isPresent()` | `TwoSecondMemoHandler` | 빈 응답 |
| `UNKNOWN` | 그 외 | `UnknownHandler` | 안내 SimpleText |

### 8.2 핸들러별 흐름

**LinkCodeHandler**
1. `botUserMappingService.resolveUserId(botUserKey)` 존재 시 → "이미 연동된 계정입니다." SimpleText.
2. `botUserMappingService.link(code, botUserKey, now)`. 성공 → "연동 완료" SimpleText.
3. `CoreException(BOT_LINK_CODE_INVALID/EXPIRED/ALREADY_USED)` → 메시지를 SimpleText로 변환.

**InstagramLinkHandler**
1. `botUserMappingService.resolveUserId(botUserKey).orElseThrow(...)` — 미연동이면 "먼저 연동코드를 입력해 주세요." SimpleText.
2. `groupMemberService.findLatestActiveGroupIdByUserId(userId)`.
3. `contentParserRegistry.resolve(url).orElseGet(empty)` → empty면 "지원하지 않는 링크" SimpleText.
4. `parser.parse(url, ctx)` → `ParsedContent(placeKeyword, ...)`.
5. `placeSearchService.searchByKeyword(placeKeyword, ctx)` → Outcome.
6. 결과별:
   - **Single**: `pinService.registerFromInstagram(userId, groupId, hit, url)`. → `twoSecondMemoSession.put(botUserKey, pinId)`. → "장소가 저장되었어요" SimpleText.
     - UNIQUE 충돌 (`DataIntegrityViolationException`) → "이미 저장된 장소입니다" SimpleText.
   - **Multiple(2~5)**: 각 후보를 `placeSelectionCandidate` 캐시에 `botUserKey:placeId` 키로 저장 (PlaceSearchHit + instagramUrl). BasicCard 리스트 응답 — 카드별 button.action="block" 또는 "message" + `extra.placeId`. 사용자가 탭 → 두 번째 webhook이 `action.params.placeId`로 도착.
   - **Empty**: "장소를 찾지 못했어요. 직접 검색해 주세요" SimpleText.

**PlaceSelectionHandler**
1. `placeId = action.params.placeId` 추출.
2. `placeSelectionCandidate.get("{botUserKey}:{placeId}")` 조회.
   - **hit**: 캐시된 `PlaceSearchHit + instagramUrl` 사용. 조회 후 즉시 `invalidate` (1회 사용).
   - **miss** (캐시 만료): `kakaoLocalClient`로 placeId 재조회는 미지원 → "선택 시간이 만료되었어요. 링크를 다시 보내 주세요" SimpleText.
3. `botUserMappingService.resolveUserId(botUserKey)` → userId. `groupMemberService.findLatestActiveGroupIdByUserId(userId)` → groupId.
4. `pinService.registerFromSelection(userId, groupId, hit, instagramUrl)`.
5. `twoSecondMemoSession.put(botUserKey, pinId)`. "장소가 저장되었어요" SimpleText.

**TwoSecondMemoHandler**
1. `pinId = twoSecondMemoSession.peek(botUserKey).get()` (classifier에서 이미 검증).
2. `userId = botUserMappingService.resolveUserId(botUserKey)`.
3. `memo = userRequest.utterance().strip()`.
4. `pinMemoService.attachAutoMemoIfWithinWindow(pinId, userId, memo)`.
5. `twoSecondMemoSession.invalidate(botUserKey)`.
6. **빈 SkillResponse 반환** (`SkillResponse.empty()` — `outputs: []`).

**UnknownHandler**
- "장소 등록은 인스타그램 링크를 보내주세요. 연동은 앱에서 발급한 6자리 숫자를 입력하세요." SimpleText.

### 8.3 PlaceSelection 좌표/이름 재조회 방식 (확정)

- **권장 채택**: `placeSelectionCandidate` Caffeine 캐시 도입 (TTL 10분, 1회 사용 후 invalidate).
- 캐시 miss 시 Kakao Local 재호출은 placeId 단건 조회 API가 없어 비현실적이므로 명시적 안내 후 종료.

## 9. 2초 룰 메모

```
[t0  ] Single 등록 완료 → twoSecondMemoSession.put(botUserKey, pinId)  ── TTL 2초
[t0+1s] 사용자 텍스트 도착
        → MessageClassifier: LINK_CODE/INSTAGRAM_LINK/PLACE_SELECTION 모두 미일치
        → peek(botUserKey) hit
        → TwoSecondMemoHandler 실행
        → PinMemoService.attachAutoMemoIfWithinWindow (조건부 UPDATE 1줄)
        → invalidate(botUserKey)
        → SkillResponse(outputs=[]) (빈 응답)
[t0+3s] 사용자 텍스트 도착 → peek empty → UNKNOWN → 안내 SimpleText
```

**race-safe 보장**: MANUAL 메모가 사전에 존재하면 `updateAutoMemoIfNotManual`이 0행 반환 → 덮어쓰기 차단 (BR-11 충족).

**race (텍스트가 등록보다 먼저)**: 분류 시점에 peek empty → UNKNOWN으로 정상 처리.

## 10. Feature Flag

```yaml
place.instagram.scraping-enabled: true   # 운영 기본 true, 인스타 차단 사고 시 false 토글
```

- `PlaceProperties.Instagram(boolean scrapingEnabled)` 매핑.
- `InstagramContentService.extract` 첫 줄에서 분기 → false면 즉시 `Optional.empty()` → 폴백 SimpleText.
- **재시작 없이 변경**하려면 Spring Cloud Config 또는 `@RefreshScope` 도입 필요(현재 범위 외). 일반 재기동으로 토글.

## 11. ErrorType 추가 (9건)

`com.wherewego.support.error.ErrorType` 에 다음 추가:

```java
/** 봇 연동 */
BOT_LINK_CODE_INVALID(HttpStatus.BAD_REQUEST, "BOT_LINK_CODE_INVALID", "유효하지 않은 연동코드입니다."),
BOT_LINK_CODE_EXPIRED(HttpStatus.GONE, "BOT_LINK_CODE_EXPIRED", "연동코드가 만료되었습니다."),
BOT_LINK_CODE_ALREADY_USED(HttpStatus.CONFLICT, "BOT_LINK_CODE_ALREADY_USED", "이미 사용된 연동코드입니다."),
BOT_USER_ALREADY_LINKED(HttpStatus.CONFLICT, "BOT_USER_ALREADY_LINKED", "이미 연동된 사용자입니다."),
BOT_SKILL_SECRET_INVALID(HttpStatus.UNAUTHORIZED, "BOT_SKILL_SECRET_INVALID", "Skill 서명이 유효하지 않습니다."),

/** 장소 */
PLC_PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "PLC_PLACE_NOT_FOUND", "장소를 찾을 수 없습니다."),
PLC_DUPLICATE_PIN(HttpStatus.CONFLICT, "PLC_DUPLICATE_PIN", "이미 저장된 장소입니다."),
PLC_KAKAO_LOCAL_FAILED(HttpStatus.BAD_GATEWAY, "PLC_KAKAO_LOCAL_FAILED", "장소 검색을 일시적으로 사용할 수 없습니다."),
PLC_INSTAGRAM_SCRAPE_FAILED(HttpStatus.BAD_GATEWAY, "PLC_INSTAGRAM_SCRAPE_FAILED", "인스타그램 데이터를 가져오지 못했습니다.");
```

### ApiControllerAdvice 추가 핸들러

```java
@ExceptionHandler
public ResponseEntity<ApiResponse<?>> handle(DataIntegrityViolationException e) {
    log.warn("DataIntegrityViolationException : {}", e.getMessage());
    return failureResponse(ErrorType.PLC_DUPLICATE_PIN, null);
}
```

- 일반 REST 컨트롤러 (`BotV1Controller`)에는 위 핸들러가 적용된다.
- **`ChatbotV1Controller`는 컨트롤러 자체 try-catch로 SimpleText 응답을 만들어 반환**하므로 advice가 트리거되지 않는다 (Spring MVC는 컨트롤러 메서드 정상 반환을 우선).

## 12. 변경 범위

### 12.1 신규 파일 목록 (제거 후 최종)

**도메인 / bot (7)**
- `com.wherewego.domain.bot.BotLinkCode`
- `com.wherewego.domain.bot.BotLinkCodeStatus`
- `com.wherewego.domain.bot.BotLinkCodeRepository`
- `com.wherewego.domain.bot.BotUserMapping`
- `com.wherewego.domain.bot.BotUserMappingRepository`
- `com.wherewego.domain.bot.BotLinkCodeService`
- `com.wherewego.domain.bot.BotUserMappingService` (+ `LinkCodeGenerator` 내부 또는 동일 패키지)

**도메인 / chatbot (8)**
- `com.wherewego.domain.chatbot.ChatbotContext`
- `com.wherewego.domain.chatbot.ChatbotWebhookService`
- `com.wherewego.domain.chatbot.MessageClassifier`
- `com.wherewego.domain.chatbot.MessageType`
- `com.wherewego.domain.chatbot.handler.MessageHandler` (인터페이스) + 5종:
  - `LinkCodeHandler`, `InstagramLinkHandler`, `PlaceSelectionHandler`, `TwoSecondMemoHandler`, `UnknownHandler`

**도메인 / place (8)**
- `com.wherewego.domain.place.parser.ContentParser`
- `com.wherewego.domain.place.parser.InstagramParser`
- `com.wherewego.domain.place.parser.ContentParserRegistry`
- `com.wherewego.domain.place.parser.ParsedContent`
- `com.wherewego.domain.place.InstagramContentService`
- `com.wherewego.domain.place.InstagramExtraction`
- `com.wherewego.domain.place.PlaceSearchService`
- `com.wherewego.domain.place.PlaceSearchOutcome` (+ `PlaceSearchHit`)

**도메인 / pin (5)**
- `com.wherewego.domain.pin.Pin`
- `com.wherewego.domain.pin.MemoSource`
- `com.wherewego.domain.pin.PinRepository`
- `com.wherewego.domain.pin.PinService`
- `com.wherewego.domain.pin.PinMemoService`

**도메인 / pin/memo (1)**
- `com.wherewego.domain.pin.memo.TwoSecondMemoSession`

**도메인 / group (3)** — Phase 3 선행 read-only
- `com.wherewego.domain.group.GroupMember`
- `com.wherewego.domain.group.GroupMemberRepository`
- `com.wherewego.domain.group.GroupMemberService`

**인프라 (약 10)**
- `com.wherewego.infrastructure.bot.BotLinkCodeRepositoryImpl` + `BotLinkCodeJpaRepository`
- `com.wherewego.infrastructure.bot.BotUserMappingRepositoryImpl` + `BotUserMappingJpaRepository`
- `com.wherewego.infrastructure.pin.PinRepositoryImpl` + `PinJpaRepository`
- `com.wherewego.infrastructure.group.GroupMemberRepositoryImpl` + `GroupMemberJpaRepository`
- `com.wherewego.infrastructure.scraper.instagram.InstagramScraperClient` (+ 이관된 `HtmlFetcher`, `MetaExtractor`, `PlaceNameExtractor`)
- `com.wherewego.infrastructure.place.kakao.KakaoLocalClient`

**Config (3)**
- `com.wherewego.config.cache.CacheConfig`
- `com.wherewego.config.env.PlaceProperties`
- `com.wherewego.config.security.KakaoSkillSecretFilter`

**Interfaces REST (6)**
- `com.wherewego.interfaces.api.bot.BotV1Controller` / `BotV1ApiSpec` / `BotV1Dto`
- `com.wherewego.interfaces.api.chatbot.ChatbotV1Controller` / `ChatbotV1ApiSpec` / `ChatbotV1Dto`

### 12.2 수정 파일

| 경로 | 변경 |
|---|---|
| `backend/apps/wherewego-api/build.gradle.kts` | `implementation("org.jsoup:jsoup:1.17.2")` 추가 |
| `backend/apps/wherewego-api/src/main/resources/application.yml` | §5.5 키 추가 |
| `backend/.env.example` | `KAKAO_LOCAL_API_KEY`, `KAKAO_SKILL_SECRET` 추가 |
| `backend/apps/wherewego-api/src/main/java/com/wherewego/config/env/KakaoApiProperties.java` | `Local`, `Skill` record 추가 |
| `backend/apps/wherewego-api/src/main/java/com/wherewego/config/security/SecurityConfig.java` | `KakaoSkillSecretFilter` 주입 + permitAll + addFilterBefore |
| `backend/apps/wherewego-api/src/main/java/com/wherewego/support/error/ErrorType.java` | 9건 추가 |
| `backend/apps/wherewego-api/src/main/java/com/wherewego/interfaces/api/ApiControllerAdvice.java` | `DataIntegrityViolationException` 핸들러 추가 |

### 12.3 이관

| 출처 | 대상 |
|---|---|
| `backend/spike/instagram-meta-scraper/.../HtmlFetcher.java` | `backend/apps/wherewego-api/src/main/java/com/wherewego/infrastructure/scraper/instagram/HtmlFetcher.java` |
| `backend/spike/instagram-meta-scraper/.../MetaExtractor.java` | 동일 디렉토리 |
| `backend/spike/instagram-meta-scraper/.../PlaceNameExtractor.java` | 동일 디렉토리 |
| `InstagramMetaSpikeRunner.java` | **이관 제외** (spike CLI 진입점, 보존 또는 삭제는 별도 결정) |

## 13. 적용 컨벤션

- **네이밍**: 패키지 `com.wherewego.{layer}.{domain}`. 클래스 PascalCase, 메서드 camelCase, ErrorType은 `DOMAIN_REASON` SCREAMING_SNAKE.
- **레이어 구조**: `domain/` (엔티티 + Repository 인터페이스 + Service) ↔ `infrastructure/` (RepositoryImpl + JpaRepository + 외부 client) ↔ `interfaces/api/` (Controller + ApiSpec + Dto) ↔ `application/` (DTO/Info — 필요 시).
- **DI**: 생성자 주입 + `@RequiredArgsConstructor` (Lombok).
- **DTO**: Java `record` 우선. 컨트롤러 DTO는 `XxxV1Dto` outer class 안에 `public record` 중첩.
- **에러 처리**: `CoreException(ErrorType, customMessage)` throw → `ApiControllerAdvice`가 일괄 변환 → `ApiResponse.fail`. **단, ChatbotV1Controller는 자체 try-catch로 SimpleText 변환.**
- **Repository 패턴**: 도메인 인터페이스 + `Impl` adapter + Spring Data `JpaRepository` 3-레이어 (참조: `UserRepository`/`UserRepositoryImpl`/`UserJpaRepository`).
- **Filter 등록**: `@Component` + `OncePerRequestFilter` 상속. (`JwtAuthenticationFilter` 참조)
- **검증**: `@Validated` + `@ConfigurationProperties` record + Bean Validation 어노테이션.

## 14. 구현 순서 (4단계)

**단계 1 — 인프라/스키마/Config (의존: 없음, 병렬 가능)**

1. `KakaoApiProperties` 확장
2. `PlaceProperties` 신규
3. `application.yml` + `.env.example` 갱신 (의존: 1, 2)
4. `build.gradle.kts` jsoup 추가
5. `ErrorType` 9건 추가
6. `CacheConfig` (twoSecondMemo + placeSelectionCandidate)
7. `ApiControllerAdvice` DataIntegrityViolationException 핸들러 (의존: 5)

**단계 2 — bot 도메인 + Skill Secret Filter (의존: 단계 1)**

8. `BotLinkCode`, `BotLinkCodeStatus`, `BotUserMapping` 엔티티
9. Repository 트리오 (port/Impl/Jpa) × 2 (의존: 8)
10. `LinkCodeGenerator` + `BotLinkCodeService` (의존: 9, 5)
11. `BotUserMappingService` (의존: 9, 10)
12. `BotV1Dto` / `BotV1ApiSpec` / `BotV1Controller` (의존: 10)
13. `KakaoSkillSecretFilter` (의존: 1)
14. `SecurityConfig` 수정 — permitAll + addFilterBefore (의존: 13)

**단계 3 — 스크래퍼 이관 + place 도메인 (의존: 단계 1)**

15. spike 3개 클래스 이관 (의존: 4)
16. `InstagramScraperClient` (의존: 15, 2)
17. `KakaoLocalClient` (의존: 1, 2)
18. `ContentParser` 인터페이스 + `ParsedContent`
19. `InstagramContentService` + `InstagramExtraction` (의존: 16, 2)
20. `InstagramParser` (의존: 18, 19)
21. `ContentParserRegistry` (의존: 18, 20)
22. `PlaceSearchService` + `PlaceSearchOutcome` + `PlaceSearchHit` (의존: 17, 2, 5)

**단계 4 — pin 도메인 + chatbot webhook (의존: 단계 2, 단계 3)**

23. `Pin` + `MemoSource` 엔티티
24. `PinRepository` 트리오 (의존: 23)
25. `PinService.registerFromInstagram/registerFromSelection` (의존: 24)
26. `PinMemoService` (조건부 UPDATE) (의존: 24)
27. `TwoSecondMemoSession` (의존: 6)
28. `GroupMember` + Repository 트리오 + `GroupMemberService`
29. `ChatbotContext` + `MessageType`
30. `MessageClassifier` (의존: 27, 29)
31. 핸들러 5종 (의존: 11, 21, 22, 25, 26, 27, 28)
32. `ChatbotWebhookService.handle` (t0 측정 + try-catch 폴백) (의존: 30, 31)
33. `ChatbotV1Dto` / `ChatbotV1ApiSpec` / `ChatbotV1Controller` (의존: 32)

병렬 실행 가능 구간:
- 단계 1의 1, 2, 4, 5, 6은 모두 의존 없음 → 동시 작업 가능.
- 단계 2의 13(필터)과 단계 3의 15~22는 단계 1만 완료되면 병렬 가능.
- 단계 4의 23, 28, 29는 의존 없이 시작 가능.

## 15. 테스트

### 15.1 단위 테스트

| 대상 | 검증 항목 |
|---|---|
| `LinkCodeGenerator` | 6자리 숫자, 0 패딩, BR-1 충돌 시 재시도 (mock Repository) |
| `MessageClassifier` | 5단계 분류 (PLACE_SELECTION 최우선, 정규식 매칭, peek 분기) |
| `PinMemoService` | `updateAutoMemoIfNotManual` 호출 결과 0/1행 분기 |
| `InstagramContentService` | feature flag false → `Optional.empty()` 즉시 반환 |
| `PlaceNameExtractor` | 우선순위 fixture (캡션 첫 줄 > og:title > meta description) |
| `KakaoSkillSecretFilter` | 헤더 일치/불일치/누락 3case |

### 15.2 통합 테스트 (testcontainers + WireMock)

| 클래스 | 시나리오 |
|---|---|
| `BotLinkCodeServiceIT` | 재발급 시 기존 ACTIVE → EXPIRED, 신규 INSERT 성공. Partial UNIQUE INDEX 검증 |
| `PinRepositoryIT` | 동일 `(group_id, instagram_url)` 중복 INSERT → `DataIntegrityViolationException` |
| `PinMemoServiceIT` | MANUAL/AUTO/NULL 사전 상태별 조건부 UPDATE 결과 (3 case) |
| `ChatbotWebhookE2ETest` | WireMock Kakao Local + Instagram stub, AC-1~18 전 시나리오 |
| `ChatbotWebhookDeadlineTest` | WireMock 6초 지연 → Empty Outcome + 폴백 SimpleText, 응답 < 5초 검증 |
| `PlaceSelectionFlowIT` | Multiple 응답 → placeSelectionCandidate 캐시 hit → 두 번째 webhook → 등록 검증 |

## 16. 5초 SLA 데드라인 정책 (상세)

| 항목 | 값/동작 |
|---|---|
| 측정 기준점 | `ChatbotWebhookService.handle` 진입 시 `t0` |
| 전체 데드라인 | `place.search.sync-deadline-ms=4500` |
| 컷오프 위치 1 | `InstagramScraperClient.fetchHtml` 진입 시 `ctx.remaining() <= 0` 검사 |
| 컷오프 위치 2 | `KakaoLocalClient.searchByKeyword` 진입 시 동일 검사 |
| 스크래핑 데드라인 초과 | `CoreException(PLC_INSTAGRAM_SCRAPE_FAILED)` → 핸들러가 `SimpleText("처리가 지연되었어요. 다시 시도해 주세요.")` 변환 + WARN 로그 |
| 검색 데드라인 초과 | `Outcome.Empty()` 반환 → "장소를 찾지 못했어요" SimpleText |
| 개별 호출 타임아웃 | `kakao.local.timeout-ms=1500`, `instagram.scraper.timeout-ms=8000` (데드라인이 우선) |

```java
public record ChatbotContext(long t0, long deadlineMs) {
    public long remaining() { return deadlineMs - (System.currentTimeMillis() - t0); }
    public boolean expired() { return remaining() <= 0; }
}
```

## 17. 보안 / 운영

| 항목 | 정책 |
|---|---|
| `KAKAO_SKILL_SECRET` | 환경변수 평문 저장. HTTPS 강제 + **분기별 1회 로테이션** (운영 메모) |
| 헤더 비교 | `MessageDigest.isEqual` (타이밍 공격 완화). 일반 `.equals()` 금지 |
| 401 응답 본문 | `{"result":"FAIL","error":{"code":"BOT_SKILL_SECRET_INVALID"}}` — CoreException 메시지 직접 노출 금지 |
| 로깅 | 데드라인 초과 / 스크래핑 실패 / 카카오 5xx → `log.warn` |
| @Async / Async Executor | **부재** — 동기 처리 일관성. 스레드 풀 고갈 위험 0 |
| Skill webhook 진입 사용자 | `permitAll` + `KakaoSkillSecretFilter`로만 가드. JWT 흐름과 완전 분리 |

## 18. 도메인 ID/명명 보강 메모

| 식별자 | 출처 | 타입 | 예 |
|---|---|---|---|
| `botUserKey` | `SkillRequest.userRequest.user.id` | String | `"kakao_abc123"` |
| `userId` | DB `user.id` | Long | `42` |
| `groupId` | DB `group.id` (Phase 3 선행) | Long | `7` |
| `code` | `BotLinkCode.code` | String CHAR(6) | `"483921"` |
| `kakaoPlaceId` | `KakaoLocalClient` 응답 `id` | String | `"26338954"` |
