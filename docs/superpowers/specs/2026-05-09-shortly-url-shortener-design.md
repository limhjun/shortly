# Shortly — URL Shortener with Click Analytics

**Status:** v1 design, approved 2026-05-09
**Owner:** hjun (limhjun.me)
**Stack:** Spring Boot 4.0.6, Java 25 LTS, Postgres 16, Gradle (Kotlin DSL)

---

## 1. Overview

A public URL shortener service. Anyone on the internet can submit a long URL and receive a short code; visiting the short code redirects to the original URL and emits a click event. An analytics endpoint exposes per-link aggregate statistics (total clicks, unique visitors, daily breakdown, top referrers).

The service is shipped as a single deployable Spring Boot application backed by a single Postgres database. The target deployment is Fly.io with managed Postgres.

## 2. Goals & Non-Goals

### Goals (v1 must-have)

1. Accept anonymous URL-shortening requests with strict per-IP rate limiting.
2. Redirect short codes to original URLs in under 50ms p95.
3. Record every redirect as a click event, off the response path (async).
4. Serve aggregate analytics (totalClicks, uniqueVisitors, byDay, topReferrers) for any code.
5. Deploy publicly with a real domain (`*.fly.dev` minimum, custom domain optional).
6. README that lets a stranger run the project locally and deploy their own copy in under 15 minutes.

### Non-Goals (explicitly deferred to v2 or later)

- URL safety scanning (Google Safe Browsing API).
- Captcha / bot challenge on URL creation.
- Takedown UI or abuse reporting endpoint.
- User accounts, authentication, or link ownership.
- Custom aliases (`/my-blog`); v1 only generates codes.
- GeoIP enrichment of analytics (country breakdown).
- Frontend / dashboard UI; API-only in v1.
- Distributed rate limiting (Redis-backed); in-process Caffeine is fine for v1's scale.

## 3. Architecture

Single Spring Boot 4.0.6 deployable, single Postgres 16 database, single JVM process. No external services in v1 beyond Postgres. Deploys as one container.

### Package layout (`limhjun.me.shortly`)

```
ShortlyApplication        — main
config/
  AsyncConfig             — @EnableAsync, virtual-thread-backed Async executor, bounded queue
  WebConfig               — CORS, error handlers
url/                      — create + redirect (write + read)
  ShortUrl
  ShortUrlRepository
  UrlService
  Base62Encoder
  UrlController
  dto/ {CreateUrlRequest, UrlResponse}
click/                    — async click tracking (write only)
  ClickEvent
  ClickEventRepository
  ClickRecordedEvent
  ClickRecorder           — @Async @EventListener
  IpHasher
analytics/                — read-side aggregations
  AnalyticsService
  AnalyticsController
  dto/ AnalyticsResponse
ratelimit/                — cross-cutting filter
  RateLimitFilter         — OncePerRequestFilter
  BucketProvider          — Caffeine cache of Bucket per IP
  RateLimitProperties     — @ConfigurationProperties
```

Feature-first packaging: a reader sees what features exist before what bean types exist. The `click` and `analytics` packages are split because they represent different concerns (write-side vs. read-side), even though both touch `click_event`. This keeps a future read-replica or CQRS separation trivial.

Cross-package coupling is minimal:
- `UrlController` publishes `ClickRecordedEvent` (defined in `click/`); the only `url → click` reach.
- `AnalyticsService` reads both repositories (a natural read-side pattern).
- `ratelimit` depends on nothing else.

Spring Modulith is not used in v1: with only four feature packages and one bounded context, modulith ceremony does not pay off. May be introduced in v2 if module count grows.

## 4. Components & Data Model

### 4.1 `ShortUrl` entity

```java
@Entity @Table(name = "short_url")
class ShortUrl {
  @Id
  @GeneratedValue(strategy = SEQUENCE, generator = "short_url_seq")
  @SequenceGenerator(name = "short_url_seq",
                     sequenceName = "short_url_seq",
                     allocationSize = 1)
  Long id;

  @Column(unique=true, nullable=false, length=11) String code;
  @Column(nullable=false, length=2048)            String originalUrl;
  @Column(nullable=false)                         Instant createdAt;
  @Column                                         Instant expiresAt; // null = never expires

  @PrePersist
  void assignCode() {
    this.code = Base62Encoder.encode(this.id); // id is already set by SEQUENCE pre-fetch
  }
}
```

**Short code generation:** Base62-encoded `id`, populated in a single INSERT.

Strategy is `SEQUENCE` (not `IDENTITY`) so Hibernate fetches the next sequence value *before* the INSERT runs — giving us a known `id` to encode in the `@PrePersist` hook. Result: one INSERT with both `id` and `code` populated, no nullable-column smell, no two-statement transaction.

The sequence starts at 100,000 so the shortest codes are 3 characters (`62² = 3844 < 100000 < 62³ = 238328`); avoids trivially scrapable 1-character codes on day one. Column length is 11 to accommodate any `Long` value (`Base62(Long.MAX_VALUE)` is 11 chars); practical IDs in this project will use 5–7 chars.

**Tradeoff:** Base62-of-ID codes are enumerable (knowing one tells you others exist nearby). Acceptable for v1 because the URLs being shortened are user-submitted public URLs. v2 may swap in Hashids if enumeration becomes a real concern.

**Indexes:** PK on `id`; unique on `code` (the redirect lookup; most-frequent query in the system).

### 4.2 `ClickEvent` entity

```java
@Entity @Table(
  name = "click_event",
  indexes = {
    @Index(name="ix_click_url_time", columnList="short_url_id, clicked_at"),
    @Index(name="ix_click_time",     columnList="clicked_at")
  }
)
class ClickEvent {
  @Id @GeneratedValue(strategy = IDENTITY) Long id;
  @Column(nullable=false)              Long    shortUrlId;     // raw FK, no JPA association
  @Column(nullable=false)              Instant clickedAt;
  @Column(nullable=false, length=64)   String  ipHash;         // SHA-256 hex
  @Column(length=2048)                 String  referrer;
  @Column(length=50)                   String  userAgentFamily; // parsed via uap-java
}
```

No `@ManyToOne` association to `ShortUrl`. The append-heavy table is only read in aggregations (always grouped by `short_url_id`); a JPA association adds lazy-fetch surprises and N+1 risks without earning its keep.

The composite `(short_url_id, clicked_at)` index serves all per-code analytics queries. The `clicked_at`-only index serves global time-series queries (out of scope for v1 endpoints, but cheap to maintain).

### 4.3 Repositories

```java
interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
  Optional<ShortUrl> findByCode(String code);
}

interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
  long countByShortUrlId(Long id);
  long countDistinctIpHashByShortUrlId(Long id);
  List<DayCount>      byDay(Long id, Instant since);
  List<ReferrerCount> topReferrers(Long id, Pageable limit);
}
```

`DayCount` and `ReferrerCount` are Spring Data interface projections (one-line declarations).

### 4.4 IP hashing

`IpHasher` produces a 64-character hex SHA-256:

- `salt = pepper + ":" + UTC_DATE_STRING`
- `hash = SHA-256(salt + ":" + ip)`

The pepper comes from `APP_IP_HASH_PEPPER` env var and is never logged. Within one day, the same IP hashes identically (so `countDistinctIpHash` counts unique daily visitors). Across days, the same IP hashes differently (no long-term cross-day tracking).

**Threat model:** if both DB and pepper leak, the IPv4 space (4B values) is brute-forceable. This is the irreducible privacy floor of "store something derivable from an IP." Documented in README; acceptable for v1.

### 4.5 Schema migrations: Flyway

- `org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-postgresql`.
- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate verifies the schema, never modifies it.
- Migrations: `src/main/resources/db/migration/V1__init.sql`, future `V2__*.sql`.
- Tests: Testcontainers Postgres + Flyway runs migrations on container startup, exercising the same code path as production.

`V1__init.sql` contains, at minimum:

- `CREATE SEQUENCE short_url_seq START 100000;`
- `CREATE TABLE short_url (...)` matching the entity, with `id BIGINT PRIMARY KEY DEFAULT nextval('short_url_seq')`.
- `CREATE TABLE click_event (...)` with `id BIGINT GENERATED ALWAYS AS IDENTITY` (no pre-allocation needed; nothing reads click_event.id before insert).
- `CREATE INDEX ix_click_url_time ON click_event(short_url_id, clicked_at);`
- `CREATE INDEX ix_click_time     ON click_event(clicked_at);`

## 5. Data Flow

### 5.1 `POST /api/urls` — create

1. Rate limit filter checks per-IP bucket (10/min). On miss → 429 + `Retry-After`.
2. Controller validates request DTO (`@Valid`). On failure → 400.
3. `UrlService.create(originalUrl)` opens a transaction. `repository.save(new ShortUrl(...))` triggers Hibernate to fetch `nextval('short_url_seq')` (giving the entity its `id`), then the `@PrePersist` hook sets `code = Base62Encoder.encode(id)`, then a single INSERT writes the row with both `id` and `code` populated. Commit.
4. Controller returns 201 with `Location: {APP_BASE_URL}/{code}` and JSON body `{code, shortUrl, originalUrl, createdAt}`.

**Idempotency:** submitting the same `originalUrl` twice creates two different short codes. Dedup is rejected because it forces clients into a shared namespace where they could discover one another's existence; "create new" is also the simpler API contract.

### 5.2 `GET /{code}` — redirect (hot path)

1. Rate limit filter checks per-IP bucket (60/min). On miss → 429.
2. `UrlService.resolve(code)` reads `ShortUrlRepository.findByCode` through a Caffeine `@Cacheable("shortUrl")` (5-min TTL, 10k entries). Cache miss → single PG SELECT on the unique `code` index.
3. If `expiresAt != null && expiresAt.isBefore(now)` → 410 Gone, no event published.
4. If not found → 404 Not Found.
5. Controller publishes `ClickRecordedEvent(shortUrlId, clickedAt, rawIp, referrer, userAgent)` to the application event bus.
6. Controller returns 302 Found with `Location: originalUrl`.
7. **Asynchronously** (on a virtual-thread executor): `ClickRecorder.@EventListener` hashes the IP, parses the UA family, inserts a `click_event` row.

Steps 5–6 happen before step 7 completes. The redirect is unaffected by click-recorder failures.

### 5.3 `GET /api/urls/{code}/stats` — analytics

1. Rate limit filter checks per-IP bucket (30/min).
2. Controller calls `AnalyticsService.stats(code)`.
3. Service resolves the short URL (404 if missing — never leak existence by returning empty stats).
4. Service issues four queries in sequence (all hit the composite index): `countByShortUrlId`, `countDistinctIpHashByShortUrlId`, `byDay(now-7d)`, `topReferrers(limit=10)`.
5. Returns 200 with `{totalClicks, uniqueVisitors, byDay[], topReferrers[]}`.

No caching at this layer in v1 (analytics should reflect current state). May add a 30s cache in v2 if it becomes a hotspot.

### 5.4 Rate limit summary

| Endpoint                       | Window | Limit (per IP) | Bucket key       |
|--------------------------------|:------:|:--------------:|------------------|
| `POST /api/urls`               | 1 min  | 10             | `create:{ip}`    |
| `GET /{code}`                  | 1 min  | 60             | `redirect:{ip}`  |
| `GET /api/urls/{code}/stats`   | 1 min  | 30             | `stats:{ip}`     |
| `GET /actuator/**`             | —      | exempt         | —                |

Buckets stored in a Caffeine LRU cache keyed by `{prefix}:{ip}` with 1-hour idle eviction.

Actuator endpoints are exempt from rate limiting because Fly.io's health prober and Prometheus scrapers come from internal addresses; rate-limiting them risks false-positive instance-down alerts. v2 will add IP-allowlist or basic-auth on `/actuator/**` to compensate.

## 6. Error Handling & Failure Modes

### 6.1 Unified error response shape

```json
{
  "type":      "validation_error",
  "message":   "originalUrl must be a valid http(s) URL",
  "timestamp": "2026-05-09T12:34:56Z",
  "path":      "/api/urls",
  "traceId":   "00f067aa0ba902b7"
}
```

Implemented as a single `@RestControllerAdvice`. `traceId` is pulled from the Micrometer Tracing context (built into Boot 4 Actuator).

### 6.2 Status code catalog

| Scenario                                | Status | Notes                                                |
|-----------------------------------------|:------:|------------------------------------------------------|
| Malformed JSON body                     | 400    | `HttpMessageNotReadableException`                    |
| `@Valid` failure on DTO                 | 400    | `MethodArgumentNotValidException`                    |
| Unknown short code                      | 404    | Generic body — don't leak existence                  |
| Expired short code                      | 410    | Distinct from 404                                    |
| Rate limit exceeded                     | 429    | `Retry-After: <seconds>` header                      |
| Postgres unavailable (read or write)    | 503    | Lets clients/CDN retry safely                        |
| Async listener failure                  | n/a    | Already responded 302; logged WARN, never user-visible |
| Anything else                           | 500    | `Exception.class` catch-all; full stack at ERROR     |

**Rule:** if a retry could plausibly succeed, return 5xx (preferably 503). Otherwise 4xx.

### 6.3 Failure isolation

**Postgres briefly unavailable.** HikariCP rejects new acquires after a short timeout — set `spring.datasource.hikari.connection-timeout=5000` (5 s, down from the 30 s default). No in-request retry; the 503 response lets the caller retry. `/actuator/health/db` flips to DOWN, surfacing automated alerts.

**Async listener fails.** The redirect already responded. Logged at WARN with `code, ipHash, exception.class, exception.message` (never raw IP). `shortly.click.recorder.errors` counter increments. No retry — lost events under-count analytics, not catastrophic.

**Async executor overflow.** Bounded queue, capacity 10,000. Beyond that, `RejectedExecutionException` is logged and counted. The redirect path never blocks on click recording.

**Caffeine cache thrash.** Cache sized 10k entries with LRU. Even under viral load on one link, the entry stays hottest. Misses fall through to a sub-1ms PG lookup. No additional handling needed.

## 7. Testing Strategy

Three-tier pyramid; CI target under 90 seconds.

### 7.1 Unit tests (no Spring context)

- `Base62EncoderTest` — round-trip property test (1000 random IDs); boundary cases (`id=0`, `id=Long.MAX_VALUE`, `id=100000`).
- `IpHasherTest` — same-IP/same-day determinism, same-IP/different-day differs, pepper rotation invalidates old hashes.
- `BucketProviderTest` — bucket isolation, refill-after-TTL, capacity boundary.
- `UrlValidatorTest` — accepts http/https; rejects `javascript:`, `data:`, `file:`, > 2048 chars.

### 7.2 Slice tests

- `@WebMvcTest(UrlController.class)` — happy + negative paths, asserts our error envelope shape.
- `@WebMvcTest(AnalyticsController.class)` — 200 shape, 404 on unknown code.
- `@DataJpaTest` — repository methods against H2; covers `findByCode`, `countDistinctIpHash`, `byDay` ordering.
- `@JsonTest` — serialization of `AnalyticsResponse`, `UrlResponse`, error envelope.

### 7.3 Integration tests (Testcontainers Postgres)

`@SpringBootTest(webEnvironment=RANDOM_PORT) + @ServiceConnection PostgreSQLContainer`. One container reused per class.

Six required scenarios:

1. End-to-end happy path (create → redirect → click recorded).
2. Async timing — Awaitility polls for the click row up to 2s.
3. Rate limit boundary — 11th POST in a minute returns 429 + `Retry-After`.
4. Expired link — 410 Gone, no click event.
5. Analytics correctness — 5 redirects from 3 distinct IP hashes → `totalClicks=5, uniqueVisitors=3`.
6. Cache behavior — second redirect skips DB SELECT (verified via Hibernate statement-counting interceptor).

### 7.4 What is NOT tested in v1

- Performance / load. Latency claims in README are qualitative.
- Chaos / failure injection. The 503 path is asserted via mocks in slice tests, not against a killed container.
- Frontend (no frontend in v1).
- Migration rollback (Flyway is forward-only in v1).

### 7.5 Coverage

JaCoCo enabled. Target ~80% line coverage overall, not gated. Focus is on **scenario coverage** at the integration layer, not line coverage at any single layer.

### 7.6 CI

GitHub Actions: `./gradlew check` on every push. Testcontainers pulls Postgres lazily — no `services:` block needed. Total runtime target: under 90 seconds.

## 8. Deployment & Operations

### 8.1 Target: Fly.io

Fly.io chosen over Railway, Render, or VPS because:

- Hard-capped free tier (no overage billing surprises).
- Managed Postgres with sane free tier (256 MB).
- ~1s cold-start on wake; tolerable.
- 256 MB instance is enough for Spring Boot 4 with virtual threads (no native compilation needed).
- Predictable forever-free.

Render's 15-minute idle sleep would kill the "I'd actually use it" demo experience; rejected.

### 8.2 Container

Multi-stage Dockerfile on `eclipse-temurin:25-{jdk,jre}-alpine`. Final image ~200 MB. Runtime flags: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -jar app.jar`. JVM heap scales with container memory, leaving 25% for native/metaspace/threads — critical on a 256 MB box.

No GraalVM native image in v1 (per scope).

### 8.3 Configuration

| File                              | Profile      | Notes                              |
|-----------------------------------|--------------|------------------------------------|
| `application.properties`          | default/dev  | H2 file-mode at `./data/dev.db`    |
| `application-prod.properties`     | prod         | Postgres, JSON logs, virtual threads |

**Prod env vars:**

| Variable                       | Purpose                       |
|--------------------------------|-------------------------------|
| `SPRING_PROFILES_ACTIVE=prod`  | activate prod profile         |
| `SPRING_DATASOURCE_URL`        | Postgres JDBC URL             |
| `SPRING_DATASOURCE_USERNAME`   | (Fly secret)                  |
| `SPRING_DATASOURCE_PASSWORD`   | (Fly secret)                  |
| `APP_IP_HASH_PEPPER`           | 32 random bytes (Fly secret)  |
| `APP_BASE_URL`                 | e.g. `https://shortly.fly.dev` |

**Prod profile sets:**

- `spring.threads.virtual.enabled=true`
- `spring.jpa.hibernate.ddl-auto=validate`
- `spring.flyway.enabled=true`
- `management.endpoints.web.exposure.include=health,info,prometheus,metrics`
- Logback JSON encoder via `logstash-logback-encoder`.

### 8.4 Observability surface

| Endpoint                              | Purpose                                       |
|---------------------------------------|-----------------------------------------------|
| `/actuator/health/liveness`           | Container/Fly health check                    |
| `/actuator/health/readiness`          | DB connectivity, ready for traffic            |
| `/actuator/info`                      | git commit hash, build time                   |
| `/actuator/prometheus`                | Micrometer metrics in Prometheus format       |
| `/actuator/metrics`                   | Browseable metric catalog                     |

**Custom metrics:**

| Metric                           | Type    | Tags        | Purpose                       |
|----------------------------------|---------|-------------|-------------------------------|
| `shortly.url.created`            | counter | —           | URL creation throughput       |
| `shortly.url.redirect`           | counter | `status`    | redirect outcomes (200/410/404) |
| `shortly.click.recorded`         | counter | —           | click events written          |
| `shortly.click.recorder.errors`  | counter | —           | async listener failures (alarmable) |
| `shortly.ratelimit.blocked`      | counter | `endpoint`  | 429s served per endpoint      |

**Tracing:** Micrometer Tracing (Bridge to OTel) enabled by default in Boot 4. Trace IDs in logs. No remote exporter in v1 (v2 may add Jaeger/Zipkin).

### 8.5 README structure

The README is part of the v1 deliverable, in this order:

1. One-line description + screenshot of `/api/urls/{code}/stats` JSON.
2. Live demo URL + curl example for create + redirect + stats.
3. Architecture diagram (the package layout from §3).
4. Local dev setup — `./gradlew bootRun` works out of the box on H2.
5. Local Postgres run — `docker compose up postgres`, then `./gradlew bootRun --args='--spring.profiles.active=prod-local'`.
6. Test — `./gradlew check`, expected ~90s.
7. Deploy your own — `fly launch && fly secrets set ...`.
8. Design decisions — link back to this document.
9. **What's NOT in v1** — explicit deferred-list (Safe Browsing, captcha, etc.) so reviewers can't fault deliberate non-goals.
10. Tech stack one-liner.

### 8.6 Cost control

- IP rate limit on every endpoint.
- Fly.io free tier is hard-capped (no overage billing).
- Postgres free tier is hard-capped (writes fail with 503 when full, instead of unbounded growth).

If abuse takes the demo down in v1, the demo goes down. We will know via the 502/503 on the live demo URL; v2 adds Safe Browsing + captcha.

## 9. v2 Roadmap (out of scope for this spec)

- Google Safe Browsing API integration on URL creation.
- Captcha (hCaptcha) on the create endpoint.
- Takedown / abuse-reporting endpoint.
- GeoIP enrichment via MaxMind GeoLite2 (with monthly DB refresh job).
- Hashids replacement for Base62-of-ID codes.
- Per-day analytics windowing (`?window=30d`).
- Distributed rate limit via Redis.
- Remote tracing exporter (Jaeger/Zipkin).
- Frontend dashboard.
- Spring Modulith if module count grows.

## 10. Open Questions

None as of approval (2026-05-09). Document any new ones below as the build progresses; update this spec doc rather than letting them rot in chat history.
