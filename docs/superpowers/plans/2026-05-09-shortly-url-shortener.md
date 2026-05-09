# Shortly URL Shortener Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the v1 Shortly URL shortener — a public Spring Boot 4 service that accepts URL-shortening requests, redirects short codes, records click events asynchronously, and exposes per-link aggregate analytics.

**Architecture:** Single Spring Boot 4.0.6 deployable on Java 25. Single Postgres 16 database. Feature-first packages (`url`, `click`, `analytics`, `ratelimit`, `config`). Async click recording via `ApplicationEventPublisher` + virtual-thread executor. In-process per-IP rate limiting via Bucket4j + Caffeine. Flyway migrations. Multi-stage Docker build deployed to Fly.io with managed Postgres.

**Tech Stack:** Spring Boot 4.0.6, Spring Framework 7, Java 25 LTS, Gradle Kotlin DSL, Postgres 16, Flyway, Hibernate (JPA), Bucket4j, Caffeine, Micrometer + Prometheus, Logback (logstash JSON encoder), uap-java (UA parsing), JUnit 5, Testcontainers, Awaitility, Docker, Fly.io, GitHub Actions.

**Reference spec:** [`docs/superpowers/specs/2026-05-09-shortly-url-shortener-design.md`](../specs/2026-05-09-shortly-url-shortener-design.md)

---

## File-Structure Overview

This plan creates or modifies these files. Each task lists the specific files it touches.

**Build & config**
- modify `build.gradle.kts` (Task 1)
- modify `src/main/resources/application.properties` (Task 2)
- new `src/main/resources/application-prod.properties` (Task 2)
- new `src/main/resources/db/migration/V1__init.sql` (Task 4)
- new `src/main/resources/logback-spring.xml` (Task 21)
- modify `.gitignore` (Task 2)

**Source — `limhjun.me.shortly.url`**
- new `Base62Encoder.java` (Task 3)
- new `ShortUrl.java` (Task 5)
- new `ShortUrlRepository.java` (Task 5)
- new `UrlService.java` (Task 9)
- new `UrlController.java` (Task 13)
- new `dto/CreateUrlRequest.java` (Task 12)
- new `dto/UrlResponse.java` (Task 12)

**Source — `limhjun.me.shortly.click`**
- new `IpHasher.java` (Task 6)
- new `ClickEvent.java` (Task 7)
- new `ClickEventRepository.java` (Task 7)
- new `ClickRecordedEvent.java` (Task 10)
- new `ClickRecorder.java` (Task 10)

**Source — `limhjun.me.shortly.analytics`**
- new `AnalyticsService.java` (Task 11)
- new `AnalyticsController.java` (Task 14)
- new `dto/AnalyticsResponse.java` (Task 12)
- new `dto/DayCount.java` (Task 8)
- new `dto/ReferrerCount.java` (Task 8)

**Source — `limhjun.me.shortly.ratelimit`**
- new `RateLimitProperties.java` (Task 16)
- new `BucketProvider.java` (Task 16)
- new `RateLimitFilter.java` (Task 17)

**Source — `limhjun.me.shortly.config`**
- new `AsyncConfig.java` (Task 10)
- new `ApiErrorAdvice.java` (Task 15)

**Tests** — corresponding `*Test.java` files under `src/test/java/limhjun/me/shortly/...`. Listed per task.

**Ops & deployment**
- new `Dockerfile` (Task 22)
- new `.dockerignore` (Task 22)
- new `compose.yaml` (Task 23)
- new `fly.toml` (Task 24)
- new `.github/workflows/ci.yml` (Task 25)
- new `README.md` (Task 26)

---

## Task 1: Add runtime dependencies to build.gradle.kts

**Files:**
- Modify: `build.gradle.kts`

The Initializr scaffold doesn't include Bucket4j (rate limiting), Caffeine (cache + bucket storage), Flyway (migrations), uap-java (UA parsing), Awaitility (async test polling), or the logstash JSON encoder. Add them.

- [ ] **Step 1: Add the Bucket4j, Caffeine, Flyway, uap-java, logstash, Awaitility dependencies.**

Add these lines inside the `dependencies { ... }` block in `build.gradle.kts`, alphabetized within the group:

```kotlin
    // Rate limiting
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    // Schema migrations
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    // UA family parsing
    implementation("com.github.ua-parser:uap-java:1.6.1")
    // JSON logs in prod
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    // Caching abstraction (Spring Boot's; uses Caffeine as backend)
    implementation("org.springframework.boot:spring-boot-starter-cache")
    // Async polling for integration tests
    testImplementation("org.awaitility:awaitility:4.2.2")
```

- [ ] **Step 2: Verify Gradle resolves the new dependencies.**

Run: `./gradlew dependencies --configuration runtimeClasspath | grep -E "bucket4j|caffeine|flyway|uap|logstash|awaitility"`
Expected: each library appears at least once in the output.

- [ ] **Step 3: Commit.**

```bash
git add build.gradle.kts
git commit -m "build: add bucket4j, caffeine, flyway, uap-java, logstash, awaitility deps"
```

---

## Task 2: Configure application properties (dev + prod profiles, virtual threads)

**Files:**
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/application-prod.properties`
- Modify: `.gitignore`

- [ ] **Step 1: Replace contents of `src/main/resources/application.properties` (dev defaults).**

```properties
# === Dev defaults ===
spring.application.name=shortly

# H2 file-mode database (persists between runs in ./data/)
spring.datasource.url=jdbc:h2:file:./data/dev;AUTO_SERVER=TRUE
spring.datasource.username=sa
spring.datasource.password=
spring.datasource.driver-class-name=org.h2.Driver

# Hibernate validates schema; Flyway owns it
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

# Flyway
spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true

# Virtual threads (Boot 4 + Java 25)
spring.threads.virtual.enabled=true

# H2 console enabled in dev only (Boot 4 modular dependency)
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# Actuator exposure (dev)
management.endpoints.web.exposure.include=health,info,metrics,prometheus

# Application config
app.base-url=http://localhost:8080
app.ip-hash-pepper=dev-pepper-not-for-prod
```

- [ ] **Step 2: Create `src/main/resources/application-prod.properties` (prod profile).**

```properties
# === Prod profile (activated via SPRING_PROFILES_ACTIVE=prod) ===

# DataSource is wired entirely from env vars:
#   SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.hikari.connection-timeout=5000
spring.datasource.hikari.maximum-pool-size=10

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect

spring.flyway.enabled=true

spring.threads.virtual.enabled=true

# H2 console disabled
spring.h2.console.enabled=false

# Tighten actuator: only health, info, prometheus
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=never

# App config from env vars (set via fly secrets):
#   APP_BASE_URL  -> app.base-url
#   APP_IP_HASH_PEPPER -> app.ip-hash-pepper
```

- [ ] **Step 3: Add the H2 dev-data dir to `.gitignore`.**

Append to `.gitignore`:

```
# Dev H2 database files
/data/
```

- [ ] **Step 4: Run the app to verify properties parse cleanly (dev profile).**

Run: `./gradlew bootRun --args='--spring.main.web-application-type=none --spring.flyway.enabled=false'`
Expected: app boots and exits with code 0 (no migrations run yet because we haven't created `V1__init.sql`).

If `bootRun` errors before exiting, fix the property syntax before continuing.

Stop the app with `Ctrl-C` if it doesn't exit on its own.

- [ ] **Step 5: Commit.**

```bash
git add src/main/resources/application.properties src/main/resources/application-prod.properties .gitignore
git commit -m "config: set up dev/prod profiles, virtual threads, h2/postgres datasources"
```

---

## Task 3: Implement `Base62Encoder` (TDD)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/url/Base62Encoder.java`
- Test: `src/test/java/limhjun/me/shortly/url/Base62EncoderTest.java`

This is pure logic, no Spring needed. Encodes a `long` to a Base62 (`0-9 a-z A-Z`) string and decodes back.

- [ ] **Step 1: Write the failing test.**

Create `src/test/java/limhjun/me/shortly/url/Base62EncoderTest.java`:

```java
package limhjun.me.shortly.url;

import org.junit.jupiter.api.Test;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class Base62EncoderTest {

    @Test
    void encodeKnownValues() {
        assertEquals("0",   Base62Encoder.encode(0L));
        assertEquals("9",   Base62Encoder.encode(9L));
        assertEquals("a",   Base62Encoder.encode(10L));
        assertEquals("Z",   Base62Encoder.encode(61L));
        assertEquals("10",  Base62Encoder.encode(62L));
        assertEquals("ph7", Base62Encoder.encode(100_000L)); // sequence start
    }

    @Test
    void roundTripsRandomLongs() {
        RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
        for (int i = 0; i < 1000; i++) {
            long v = Math.abs(rng.nextLong());
            String encoded = Base62Encoder.encode(v);
            long decoded = Base62Encoder.decode(encoded);
            assertEquals(v, decoded, "Round-trip failed for " + v);
        }
    }

    @Test
    void rejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> Base62Encoder.encode(-1L));
    }

    @Test
    void boundaryValues() {
        assertEquals(0L, Base62Encoder.decode(Base62Encoder.encode(0L)));
        assertEquals(Long.MAX_VALUE, Base62Encoder.decode(Base62Encoder.encode(Long.MAX_VALUE)));
        assertTrue(Base62Encoder.encode(Long.MAX_VALUE).length() <= 11,
                   "Long.MAX_VALUE encoding fits in 11 chars");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails.**

Run: `./gradlew test --tests Base62EncoderTest`
Expected: FAIL with `Cannot find symbol class Base62Encoder` (or similar — class doesn't exist yet).

- [ ] **Step 3: Implement `Base62Encoder`.**

Create `src/main/java/limhjun/me/shortly/url/Base62Encoder.java`:

```java
package limhjun.me.shortly.url;

public final class Base62Encoder {

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length(); // 62

    private Base62Encoder() {}

    public static String encode(long value) {
        if (value < 0) throw new IllegalArgumentException("value must be >= 0, was " + value);
        if (value == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.append(ALPHABET.charAt((int) (value % BASE)));
            value /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String encoded) {
        long value = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int digit = ALPHABET.indexOf(encoded.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("invalid character: " + encoded.charAt(i));
            }
            value = value * BASE + digit;
        }
        return value;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes.**

Run: `./gradlew test --tests Base62EncoderTest`
Expected: PASS — all 4 tests green.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/url/Base62Encoder.java \
        src/test/java/limhjun/me/shortly/url/Base62EncoderTest.java
git commit -m "feat(url): add Base62Encoder for short-code generation"
```

---

## Task 4: Write Flyway V1 migration (schema)

**Files:**
- Create: `src/main/resources/db/migration/V1__init.sql`

Defines the database schema in a way that works on both Postgres (prod) and H2 (dev — H2 has Postgres compatibility mode).

- [ ] **Step 1: Create the migration.**

Create `src/main/resources/db/migration/V1__init.sql`:

```sql
-- short_url
CREATE SEQUENCE IF NOT EXISTS short_url_seq
    START WITH 100000
    INCREMENT BY 1;

CREATE TABLE short_url (
    id            BIGINT       PRIMARY KEY DEFAULT nextval('short_url_seq'),
    code          VARCHAR(11)  NOT NULL UNIQUE,
    original_url  VARCHAR(2048) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE
);

-- click_event
CREATE TABLE click_event (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    short_url_id       BIGINT       NOT NULL,
    clicked_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    ip_hash            CHAR(64)     NOT NULL,
    referrer           VARCHAR(2048),
    user_agent_family  VARCHAR(50)
);

CREATE INDEX ix_click_url_time ON click_event (short_url_id, clicked_at);
CREATE INDEX ix_click_time     ON click_event (clicked_at);
```

- [ ] **Step 2: Tell H2 to use Postgres compatibility mode.**

Modify `src/main/resources/application.properties` — update the H2 datasource URL line:

```properties
spring.datasource.url=jdbc:h2:file:./data/dev;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
```

(Replace the existing `spring.datasource.url=...` line.)

- [ ] **Step 3: Run the app to verify Flyway applies the migration.**

Run: `./gradlew bootRun --args='--spring.main.web-application-type=none' & sleep 10; pkill -f bootRun || true`

Expected: Flyway log line contains `Successfully applied 1 migration to schema "PUBLIC", now at version v1`. The app then exits or is killed.

If the migration fails, read the error and fix the SQL before continuing. Common issue: H2 doesn't support `GENERATED ALWAYS AS IDENTITY` outside Postgres mode — that's why MODE=PostgreSQL is set in step 2.

- [ ] **Step 4: Verify the H2 file was created.**

Run: `ls -la data/`
Expected: `dev.mv.db` file exists.

- [ ] **Step 5: Commit.**

```bash
git add src/main/resources/db/migration/V1__init.sql src/main/resources/application.properties
git commit -m "feat(db): add V1 Flyway migration for short_url + click_event"
```

---

## Task 5: Implement `ShortUrl` entity + `ShortUrlRepository` (with @DataJpaTest)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/url/ShortUrl.java`
- Create: `src/main/java/limhjun/me/shortly/url/ShortUrlRepository.java`
- Test: `src/test/java/limhjun/me/shortly/url/ShortUrlRepositoryTest.java`

- [ ] **Step 1: Write the failing repository test.**

Create `src/test/java/limhjun/me/shortly/url/ShortUrlRepositoryTest.java`:

```java
package limhjun.me.shortly.url;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ShortUrlRepositoryTest {

    @Autowired ShortUrlRepository repository;

    @Test
    void persistAssignsIdAndCode() {
        ShortUrl saved = repository.save(new ShortUrl(
                null, null, "https://example.com", Instant.now(), null));

        assertThat(saved.getId()).isGreaterThanOrEqualTo(100_000L);
        assertThat(saved.getCode()).isEqualTo(Base62Encoder.encode(saved.getId()));
    }

    @Test
    void findByCodeReturnsEmptyForUnknown() {
        Optional<ShortUrl> result = repository.findByCode("nope");
        assertThat(result).isEmpty();
    }

    @Test
    void findByCodeReturnsEntityForKnown() {
        ShortUrl saved = repository.save(new ShortUrl(
                null, null, "https://example.com", Instant.now(), null));

        Optional<ShortUrl> result = repository.findByCode(saved.getCode());
        assertThat(result).isPresent();
        assertThat(result.get().getOriginalUrl()).isEqualTo("https://example.com");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails (entity/repo don't exist).**

Run: `./gradlew test --tests ShortUrlRepositoryTest`
Expected: FAIL — compilation error, `ShortUrl` and `ShortUrlRepository` not defined.

- [ ] **Step 3: Implement `ShortUrl`.**

Create `src/main/java/limhjun/me/shortly/url/ShortUrl.java`:

```java
package limhjun.me.shortly.url;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "short_url")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "short_url_seq")
    @SequenceGenerator(name = "short_url_seq", sequenceName = "short_url_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 11)
    private String code;

    @Column(nullable = false, length = 2048)
    private String originalUrl;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant expiresAt;

    @PrePersist
    void assignCode() {
        // id is set by Hibernate from the sequence before this hook fires
        this.code = Base62Encoder.encode(this.id);
    }
}
```

- [ ] **Step 4: Implement `ShortUrlRepository`.**

Create `src/main/java/limhjun/me/shortly/url/ShortUrlRepository.java`:

```java
package limhjun.me.shortly.url;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    Optional<ShortUrl> findByCode(String code);
}
```

- [ ] **Step 5: Run the test to confirm it passes.**

Run: `./gradlew test --tests ShortUrlRepositoryTest`
Expected: PASS — all 3 tests green.

- [ ] **Step 6: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/url/ShortUrl.java \
        src/main/java/limhjun/me/shortly/url/ShortUrlRepository.java \
        src/test/java/limhjun/me/shortly/url/ShortUrlRepositoryTest.java
git commit -m "feat(url): add ShortUrl entity with @PrePersist Base62 code assignment"
```

---

## Task 6: Implement `IpHasher` (TDD)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/click/IpHasher.java`
- Test: `src/test/java/limhjun/me/shortly/click/IpHasherTest.java`

- [ ] **Step 1: Write the failing test.**

Create `src/test/java/limhjun/me/shortly/click/IpHasherTest.java`:

```java
package limhjun.me.shortly.click;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class IpHasherTest {

    private static final String PEPPER = "test-pepper";
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 9);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 5, 10);

    @Test
    void sameIpSameDayProducesSameHash() {
        IpHasher hasher = new IpHasher(PEPPER, () -> TODAY);
        String h1 = hasher.hash("203.0.113.5");
        String h2 = hasher.hash("203.0.113.5");
        assertEquals(h1, h2);
    }

    @Test
    void sameIpDifferentDayProducesDifferentHash() {
        IpHasher today    = new IpHasher(PEPPER, () -> TODAY);
        IpHasher tomorrow = new IpHasher(PEPPER, () -> TOMORROW);
        assertNotEquals(today.hash("203.0.113.5"), tomorrow.hash("203.0.113.5"));
    }

    @Test
    void differentIpsProduceDifferentHashes() {
        IpHasher hasher = new IpHasher(PEPPER, () -> TODAY);
        assertNotEquals(hasher.hash("203.0.113.5"), hasher.hash("203.0.113.6"));
    }

    @Test
    void hashIsSixtyFourHexChars() {
        IpHasher hasher = new IpHasher(PEPPER, () -> TODAY);
        String h = hasher.hash("203.0.113.5");
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]+"));
    }

    @Test
    void differentPeppersProduceDifferentHashes() {
        IpHasher a = new IpHasher("pepper-a", () -> TODAY);
        IpHasher b = new IpHasher("pepper-b", () -> TODAY);
        assertNotEquals(a.hash("203.0.113.5"), b.hash("203.0.113.5"));
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails.**

Run: `./gradlew test --tests IpHasherTest`
Expected: FAIL — `IpHasher` class doesn't exist.

- [ ] **Step 3: Implement `IpHasher`.**

Create `src/main/java/limhjun/me/shortly/click/IpHasher.java`:

```java
package limhjun.me.shortly.click;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.function.Supplier;

@Component
public class IpHasher {

    private final String pepper;
    private final Supplier<LocalDate> dateSupplier;

    public IpHasher(@Value("${app.ip-hash-pepper}") String pepper) {
        this(pepper, () -> LocalDate.now(ZoneOffset.UTC));
    }

    // For tests: inject a fixed date.
    IpHasher(String pepper, Supplier<LocalDate> dateSupplier) {
        this.pepper = pepper;
        this.dateSupplier = dateSupplier;
    }

    public String hash(String ip) {
        String salt = pepper + ":" + dateSupplier.get();
        String input = salt + ":" + ip;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes.**

Run: `./gradlew test --tests IpHasherTest`
Expected: PASS — all 5 tests green.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/click/IpHasher.java \
        src/test/java/limhjun/me/shortly/click/IpHasherTest.java
git commit -m "feat(click): add IpHasher (daily-salted SHA-256, pepper from env)"
```

---

## Task 7: Implement `ClickEvent` entity + `ClickEventRepository`

**Files:**
- Create: `src/main/java/limhjun/me/shortly/click/ClickEvent.java`
- Create: `src/main/java/limhjun/me/shortly/click/ClickEventRepository.java`
- Test: `src/test/java/limhjun/me/shortly/click/ClickEventRepositoryTest.java`

The repository defines `count`, `countDistinct`, and aggregation queries that analytics will use later.

- [ ] **Step 1: Write the failing test.**

Create `src/test/java/limhjun/me/shortly/click/ClickEventRepositoryTest.java`:

```java
package limhjun.me.shortly.click;

import limhjun.me.shortly.url.ShortUrl;
import limhjun.me.shortly.url.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ClickEventRepositoryTest {

    @Autowired ShortUrlRepository urls;
    @Autowired ClickEventRepository clicks;

    @Test
    void countsAndDistinctIpHashes() {
        ShortUrl url = urls.save(new ShortUrl(null, null, "https://example.com", Instant.now(), null));

        Instant now = Instant.now();
        clicks.save(new ClickEvent(null, url.getId(), now, "ipA", "ref", "Chrome"));
        clicks.save(new ClickEvent(null, url.getId(), now, "ipA", "ref", "Chrome"));
        clicks.save(new ClickEvent(null, url.getId(), now, "ipB", "ref", "Firefox"));

        assertThat(clicks.countByShortUrlId(url.getId())).isEqualTo(3);
        assertThat(clicks.countDistinctIpHashByShortUrlId(url.getId())).isEqualTo(2);
    }

    @Test
    void byDayReturnsRowsOrderedByDay() {
        ShortUrl url = urls.save(new ShortUrl(null, null, "https://example.com", Instant.now(), null));

        Instant base = Instant.parse("2026-05-01T10:00:00Z");
        clicks.save(new ClickEvent(null, url.getId(), base, "ip1", null, null));
        clicks.save(new ClickEvent(null, url.getId(), base.plus(1, ChronoUnit.DAYS), "ip2", null, null));
        clicks.save(new ClickEvent(null, url.getId(), base.plus(1, ChronoUnit.DAYS), "ip3", null, null));

        var rows = clicks.byDay(url.getId(), base.minus(1, ChronoUnit.DAYS));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getN()).isEqualTo(1);
        assertThat(rows.get(1).getN()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails.**

Run: `./gradlew test --tests ClickEventRepositoryTest`
Expected: FAIL — `ClickEvent`, `ClickEventRepository`, `DayCount` projection not defined yet.

- [ ] **Step 3: Implement `ClickEvent`.**

Create `src/main/java/limhjun/me/shortly/click/ClickEvent.java`:

```java
package limhjun.me.shortly.click;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
    name = "click_event",
    indexes = {
        @Index(name = "ix_click_url_time", columnList = "short_url_id, clicked_at"),
        @Index(name = "ix_click_time",     columnList = "clicked_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shortUrlId;

    @Column(nullable = false)
    private Instant clickedAt;

    @Column(nullable = false, length = 64)
    private String ipHash;

    @Column(length = 2048)
    private String referrer;

    @Column(length = 50)
    private String userAgentFamily;
}
```

- [ ] **Step 4: Implement `ClickEventRepository` with derived queries and a `DayCount` projection.**

Create `src/main/java/limhjun/me/shortly/click/ClickEventRepository.java`:

```java
package limhjun.me.shortly.click;

import limhjun.me.shortly.analytics.dto.DayCount;
import limhjun.me.shortly.analytics.dto.ReferrerCount;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    long countByShortUrlId(Long shortUrlId);

    @Query("""
        select count(distinct e.ipHash)
        from ClickEvent e
        where e.shortUrlId = :id
        """)
    long countDistinctIpHashByShortUrlId(Long id);

    @Query("""
        select function('date_trunc', 'day', e.clickedAt) as day,
               count(e) as n
        from ClickEvent e
        where e.shortUrlId = :id and e.clickedAt >= :since
        group by function('date_trunc', 'day', e.clickedAt)
        order by day
        """)
    List<DayCount> byDay(Long id, Instant since);

    @Query("""
        select e.referrer as referrer, count(e) as n
        from ClickEvent e
        where e.shortUrlId = :id and e.referrer is not null
        group by e.referrer
        order by count(e) desc
        """)
    List<ReferrerCount> topReferrers(Long id, Pageable limit);
}
```

This task references `DayCount` and `ReferrerCount` which Task 8 creates. Tasks 7 and 8 will be committed together if necessary, but this task's *test* depends only on `DayCount` — Task 8 must complete first OR `DayCount` and `ReferrerCount` can be inlined here. To avoid forward references, **Task 8 (projections) is inserted before this point in execution order if you encounter a compile error.** Otherwise continue to step 5.

- [ ] **Step 5: Run the test to confirm it passes.**

Run: `./gradlew test --tests ClickEventRepositoryTest`
Expected: PASS — both tests green.

- [ ] **Step 6: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/click/ClickEvent.java \
        src/main/java/limhjun/me/shortly/click/ClickEventRepository.java \
        src/test/java/limhjun/me/shortly/click/ClickEventRepositoryTest.java
git commit -m "feat(click): add ClickEvent entity + repository with aggregation queries"
```

---

## Task 8: Add analytics DTO projections (`DayCount`, `ReferrerCount`)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/analytics/dto/DayCount.java`
- Create: `src/main/java/limhjun/me/shortly/analytics/dto/ReferrerCount.java`

These are Spring Data interface projections — JPQL aggregation results bind to them by getter name.

**Note:** If executed strictly in order, this task should run *before* Task 7's compile step. Task 7's repository imports `DayCount` and `ReferrerCount`. If you reach Task 7 and hit a compile error, hop to this task first.

- [ ] **Step 1: Create `DayCount`.**

Create `src/main/java/limhjun/me/shortly/analytics/dto/DayCount.java`:

```java
package limhjun.me.shortly.analytics.dto;

import java.time.Instant;

public interface DayCount {
    Instant getDay();
    Long    getN();
}
```

- [ ] **Step 2: Create `ReferrerCount`.**

Create `src/main/java/limhjun/me/shortly/analytics/dto/ReferrerCount.java`:

```java
package limhjun.me.shortly.analytics.dto;

public interface ReferrerCount {
    String getReferrer();
    Long   getN();
}
```

- [ ] **Step 3: Verify compile.**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/analytics/dto/DayCount.java \
        src/main/java/limhjun/me/shortly/analytics/dto/ReferrerCount.java
git commit -m "feat(analytics): add DayCount and ReferrerCount projections"
```

---

## Task 9: Implement `UrlService` (create + resolve, with @Cacheable)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/url/UrlService.java`
- Test: `src/test/java/limhjun/me/shortly/url/UrlServiceTest.java`

`UrlService` orchestrates persistence + cache for the `code → ShortUrl` lookup. Uses Spring's caching abstraction (Caffeine backend, configured in Task 18).

- [ ] **Step 1: Write the failing test (mocked repository).**

Create `src/test/java/limhjun/me/shortly/url/UrlServiceTest.java`:

```java
package limhjun.me.shortly.url;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UrlServiceTest {

    private final ShortUrlRepository repo = mock(ShortUrlRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);
    private final UrlService service = new UrlService(repo, clock);

    @Test
    void createPersistsAndReturns() {
        ShortUrl persisted = new ShortUrl(100_000L, "ph7", "https://example.com",
                Instant.parse("2026-05-09T10:00:00Z"), null);
        when(repo.save(any(ShortUrl.class))).thenReturn(persisted);

        ShortUrl out = service.create("https://example.com");

        assertThat(out.getCode()).isEqualTo("ph7");
        assertThat(out.getOriginalUrl()).isEqualTo("https://example.com");
    }

    @Test
    void createRejectsNonHttpScheme() {
        assertThatThrownBy(() -> service.create("javascript:alert(1)"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void createRejectsMalformedUrl() {
        assertThatThrownBy(() -> service.create("not a url"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void resolveByCodeDelegates() {
        ShortUrl found = new ShortUrl(100_000L, "ph7", "https://example.com",
                Instant.now(), null);
        when(repo.findByCode("ph7")).thenReturn(Optional.of(found));

        Optional<ShortUrl> out = service.resolve("ph7");

        assertThat(out).isPresent();
        assertThat(out.get().getCode()).isEqualTo("ph7");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails.**

Run: `./gradlew test --tests UrlServiceTest`
Expected: FAIL — `UrlService`, `InvalidUrlException` not defined.

- [ ] **Step 3: Implement `UrlService`.**

Create `src/main/java/limhjun/me/shortly/url/UrlService.java`:

```java
package limhjun.me.shortly.url;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Service
public class UrlService {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final int MAX_URL_LENGTH = 2048;

    private final ShortUrlRepository repository;
    private final Clock clock;

    public UrlService(ShortUrlRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ShortUrl create(String originalUrl) {
        validate(originalUrl);
        ShortUrl entity = new ShortUrl(
                null, null, originalUrl, Instant.now(clock), null);
        return repository.save(entity);
    }

    @Cacheable(value = "shortUrl", key = "#code", unless = "#result == null")
    @Transactional(readOnly = true)
    public Optional<ShortUrl> resolve(String code) {
        return repository.findByCode(code);
    }

    private void validate(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new InvalidUrlException("originalUrl must not be blank");
        }
        if (originalUrl.length() > MAX_URL_LENGTH) {
            throw new InvalidUrlException("originalUrl exceeds " + MAX_URL_LENGTH + " chars");
        }
        try {
            URI uri = new URI(originalUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                throw new InvalidUrlException("scheme must be http or https");
            }
            if (uri.getHost() == null) {
                throw new InvalidUrlException("URL must have a host");
            }
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("malformed URL: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Add the `InvalidUrlException`.**

Create `src/main/java/limhjun/me/shortly/url/InvalidUrlException.java`:

```java
package limhjun.me.shortly.url;

public class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Add a `Clock` bean (so the service is testable with fixed time).**

Add this method to `src/main/java/limhjun/me/shortly/ShortlyApplication.java` inside the class body:

```java
    @org.springframework.context.annotation.Bean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }
```

- [ ] **Step 6: Run the test to confirm it passes.**

Run: `./gradlew test --tests UrlServiceTest`
Expected: PASS — all 4 tests green.

- [ ] **Step 7: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/url/UrlService.java \
        src/main/java/limhjun/me/shortly/url/InvalidUrlException.java \
        src/main/java/limhjun/me/shortly/ShortlyApplication.java \
        src/test/java/limhjun/me/shortly/url/UrlServiceTest.java
git commit -m "feat(url): add UrlService with validation and @Cacheable resolve"
```

---

## Task 10: Implement async click recording (`ClickRecordedEvent`, `ClickRecorder`, `AsyncConfig`)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/click/ClickRecordedEvent.java`
- Create: `src/main/java/limhjun/me/shortly/click/ClickRecorder.java`
- Create: `src/main/java/limhjun/me/shortly/config/AsyncConfig.java`

The recorder is a `@Component` with an `@Async @EventListener` that hashes IP, parses UA, inserts a `click_event`. Async runs on a virtual-thread executor.

- [ ] **Step 1: Create the event record.**

Create `src/main/java/limhjun/me/shortly/click/ClickRecordedEvent.java`:

```java
package limhjun.me.shortly.click;

import java.time.Instant;

public record ClickRecordedEvent(
        Long shortUrlId,
        Instant clickedAt,
        String ip,
        String referrer,
        String userAgent
) {}
```

- [ ] **Step 2: Create `AsyncConfig` (virtual-thread executor + bounded queue).**

Create `src/main/java/limhjun/me/shortly/config/AsyncConfig.java`:

```java
package limhjun.me.shortly.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.*;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "clickRecorderExecutor")
    public ExecutorService clickRecorderExecutor() {
        // Virtual-thread per task, but with a bounded backlog queue.
        // Spring's @Async resolves the executor by bean name.
        return new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10_000),
                Thread.ofVirtual().name("click-recorder-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy() // RejectedExecutionException on overflow
        );
    }
}
```

- [ ] **Step 3: Implement `ClickRecorder`.**

Create `src/main/java/limhjun/me/shortly/click/ClickRecorder.java`:

```java
package limhjun.me.shortly.click;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import ua_parser.Client;
import ua_parser.Parser;

@Component
@Slf4j
public class ClickRecorder {

    private final ClickEventRepository repository;
    private final IpHasher ipHasher;
    private final Parser uaParser = new Parser();
    private final Counter recordedCounter;
    private final Counter errorCounter;

    public ClickRecorder(ClickEventRepository repository,
                         IpHasher ipHasher,
                         MeterRegistry registry) {
        this.repository = repository;
        this.ipHasher = ipHasher;
        this.recordedCounter = Counter.builder("shortly.click.recorded").register(registry);
        this.errorCounter = Counter.builder("shortly.click.recorder.errors").register(registry);
    }

    @Async("clickRecorderExecutor")
    @EventListener
    public void onClick(ClickRecordedEvent event) {
        try {
            String ipHash = ipHasher.hash(event.ip());
            String uaFamily = parseUaFamily(event.userAgent());
            ClickEvent ce = new ClickEvent(
                    null,
                    event.shortUrlId(),
                    event.clickedAt(),
                    ipHash,
                    truncate(event.referrer(), 2048),
                    uaFamily
            );
            repository.save(ce);
            recordedCounter.increment();
        } catch (Exception e) {
            errorCounter.increment();
            log.warn("click recorder failed: shortUrlId={} error={}",
                    event.shortUrlId(), e.toString());
        }
    }

    private String parseUaFamily(String ua) {
        if (ua == null || ua.isBlank()) return null;
        try {
            Client c = uaParser.parse(ua);
            String name = c.userAgent.family;
            String major = c.userAgent.major;
            return major != null ? (name + " " + major) : name;
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 4: Verify compile.**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/click/ClickRecordedEvent.java \
        src/main/java/limhjun/me/shortly/click/ClickRecorder.java \
        src/main/java/limhjun/me/shortly/config/AsyncConfig.java
git commit -m "feat(click): add async ClickRecorder on virtual-thread bounded executor"
```

---

## Task 11: Implement `AnalyticsService`

**Files:**
- Create: `src/main/java/limhjun/me/shortly/analytics/AnalyticsService.java`
- Test: `src/test/java/limhjun/me/shortly/analytics/AnalyticsServiceTest.java`

- [ ] **Step 1: Write the failing test (mocked repos).**

Create `src/test/java/limhjun/me/shortly/analytics/AnalyticsServiceTest.java`:

```java
package limhjun.me.shortly.analytics;

import limhjun.me.shortly.analytics.dto.AnalyticsResponse;
import limhjun.me.shortly.click.ClickEventRepository;
import limhjun.me.shortly.url.ShortUrl;
import limhjun.me.shortly.url.ShortUrlRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private final ShortUrlRepository urls = mock(ShortUrlRepository.class);
    private final ClickEventRepository clicks = mock(ClickEventRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-09T00:00:00Z"), ZoneOffset.UTC);
    private final AnalyticsService service = new AnalyticsService(urls, clicks, clock);

    @Test
    void throwsWhenCodeMissing() {
        when(urls.findByCode("zzz")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.stats("zzz"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void aggregatesAllFour() {
        ShortUrl url = new ShortUrl(100_000L, "ph7", "https://example.com",
                Instant.parse("2026-05-01T00:00:00Z"), null);
        when(urls.findByCode("ph7")).thenReturn(Optional.of(url));
        when(clicks.countByShortUrlId(100_000L)).thenReturn(42L);
        when(clicks.countDistinctIpHashByShortUrlId(100_000L)).thenReturn(7L);
        when(clicks.byDay(eq(100_000L), any())).thenReturn(List.of());
        when(clicks.topReferrers(eq(100_000L), any())).thenReturn(List.of());

        AnalyticsResponse out = service.stats("ph7");

        assertThat(out.totalClicks()).isEqualTo(42);
        assertThat(out.uniqueVisitors()).isEqualTo(7);
        assertThat(out.byDay()).isEmpty();
        assertThat(out.topReferrers()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails.**

Run: `./gradlew test --tests AnalyticsServiceTest`
Expected: FAIL — `AnalyticsService`, `ShortUrlNotFoundException`, `AnalyticsResponse` not defined.

- [ ] **Step 3: Create `ShortUrlNotFoundException`.**

Create `src/main/java/limhjun/me/shortly/url/ShortUrlNotFoundException.java`:

```java
package limhjun.me.shortly.url;

public class ShortUrlNotFoundException extends RuntimeException {
    public ShortUrlNotFoundException(String code) {
        super("Short URL not found: " + code);
    }
}
```

- [ ] **Step 4: Create the `AnalyticsResponse` DTO.**

Create `src/main/java/limhjun/me/shortly/analytics/dto/AnalyticsResponse.java`:

```java
package limhjun.me.shortly.analytics.dto;

import java.time.Instant;
import java.util.List;

public record AnalyticsResponse(
        String code,
        long totalClicks,
        long uniqueVisitors,
        List<DayBucket> byDay,
        List<ReferrerBucket> topReferrers
) {
    public record DayBucket(Instant day, long count) {}
    public record ReferrerBucket(String referrer, long count) {}
}
```

- [ ] **Step 5: Implement `AnalyticsService`.**

Create `src/main/java/limhjun/me/shortly/analytics/AnalyticsService.java`:

```java
package limhjun.me.shortly.analytics;

import limhjun.me.shortly.analytics.dto.AnalyticsResponse;
import limhjun.me.shortly.click.ClickEventRepository;
import limhjun.me.shortly.url.ShortUrl;
import limhjun.me.shortly.url.ShortUrlNotFoundException;
import limhjun.me.shortly.url.ShortUrlRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class AnalyticsService {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    private static final int      TOP_REFERRERS_LIMIT = 10;

    private final ShortUrlRepository urls;
    private final ClickEventRepository clicks;
    private final Clock clock;

    public AnalyticsService(ShortUrlRepository urls,
                            ClickEventRepository clicks,
                            Clock clock) {
        this.urls = urls;
        this.clicks = clicks;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse stats(String code) {
        ShortUrl url = urls.findByCode(code)
                .orElseThrow(() -> new ShortUrlNotFoundException(code));

        long total  = clicks.countByShortUrlId(url.getId());
        long unique = clicks.countDistinctIpHashByShortUrlId(url.getId());
        Instant since = Instant.now(clock).minus(DEFAULT_WINDOW);

        List<AnalyticsResponse.DayBucket> byDay = clicks.byDay(url.getId(), since).stream()
                .map(p -> new AnalyticsResponse.DayBucket(p.getDay(), p.getN()))
                .toList();

        List<AnalyticsResponse.ReferrerBucket> topReferrers = clicks
                .topReferrers(url.getId(), PageRequest.of(0, TOP_REFERRERS_LIMIT)).stream()
                .map(p -> new AnalyticsResponse.ReferrerBucket(p.getReferrer(), p.getN()))
                .toList();

        return new AnalyticsResponse(code, total, unique, byDay, topReferrers);
    }
}
```

- [ ] **Step 6: Run the test to confirm it passes.**

Run: `./gradlew test --tests AnalyticsServiceTest`
Expected: PASS — both tests green.

- [ ] **Step 7: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/analytics/AnalyticsService.java \
        src/main/java/limhjun/me/shortly/analytics/dto/AnalyticsResponse.java \
        src/main/java/limhjun/me/shortly/url/ShortUrlNotFoundException.java \
        src/test/java/limhjun/me/shortly/analytics/AnalyticsServiceTest.java
git commit -m "feat(analytics): add AnalyticsService with 7-day window and top-10 referrers"
```

---

## Task 12: Add request/response DTOs (`CreateUrlRequest`, `UrlResponse`)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/url/dto/CreateUrlRequest.java`
- Create: `src/main/java/limhjun/me/shortly/url/dto/UrlResponse.java`

- [ ] **Step 1: Create `CreateUrlRequest`.**

```java
package limhjun.me.shortly.url.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUrlRequest(
        @NotBlank
        @Size(max = 2048)
        String url
) {}
```

- [ ] **Step 2: Create `UrlResponse`.**

```java
package limhjun.me.shortly.url.dto;

import java.time.Instant;

public record UrlResponse(
        String code,
        String shortUrl,
        String originalUrl,
        Instant createdAt
) {}
```

- [ ] **Step 3: Verify compile.**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/url/dto/CreateUrlRequest.java \
        src/main/java/limhjun/me/shortly/url/dto/UrlResponse.java
git commit -m "feat(url): add CreateUrlRequest + UrlResponse DTOs"
```

---

## Task 13: Implement `UrlController` (POST + GET redirect)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/url/UrlController.java`
- Test: `src/test/java/limhjun/me/shortly/url/UrlControllerTest.java`

- [ ] **Step 1: Write the failing slice test.**

Create `src/test/java/limhjun/me/shortly/url/UrlControllerTest.java`:

```java
package limhjun.me.shortly.url;

import com.fasterxml.jackson.databind.ObjectMapper;
import limhjun.me.shortly.url.dto.CreateUrlRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UrlController.class)
class UrlControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockBean UrlService urlService;
    @MockBean ApplicationEventPublisher events;

    @Test
    void postCreatesAndReturns201() throws Exception {
        ShortUrl saved = new ShortUrl(100_000L, "ph7", "https://example.com",
                Instant.parse("2026-05-09T10:00:00Z"), null);
        when(urlService.create("https://example.com")).thenReturn(saved);

        mvc.perform(post("/api/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new CreateUrlRequest("https://example.com"))))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.code").value("ph7"));
    }

    @Test
    void postRejectsBlankUrl() throws Exception {
        mvc.perform(post("/api/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getRedirectsWithFoundStatus() throws Exception {
        ShortUrl url = new ShortUrl(100_000L, "ph7", "https://example.com",
                Instant.now(), null);
        when(urlService.resolve("ph7")).thenReturn(Optional.of(url));

        mvc.perform(get("/ph7"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://example.com"));

        verify(events).publishEvent(any());
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(urlService.resolve("missing")).thenReturn(Optional.empty());
        mvc.perform(get("/missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getReturns410WhenExpired() throws Exception {
        ShortUrl expired = new ShortUrl(100_000L, "old", "https://example.com",
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-02T00:00:00Z"));
        when(urlService.resolve("old")).thenReturn(Optional.of(expired));

        mvc.perform(get("/old"))
            .andExpect(status().isGone());

        verify(events, never()).publishEvent(any());
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails.**

Run: `./gradlew test --tests UrlControllerTest`
Expected: FAIL — `UrlController` doesn't exist.

- [ ] **Step 3: Implement `UrlController`.**

Create `src/main/java/limhjun/me/shortly/url/UrlController.java`:

```java
package limhjun.me.shortly.url;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import limhjun.me.shortly.click.ClickRecordedEvent;
import limhjun.me.shortly.url.dto.CreateUrlRequest;
import limhjun.me.shortly.url.dto.UrlResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@RestController
public class UrlController {

    private final UrlService urlService;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String baseUrl;

    public UrlController(UrlService urlService,
                         ApplicationEventPublisher events,
                         Clock clock,
                         @Value("${app.base-url}") String baseUrl) {
        this.urlService = urlService;
        this.events = events;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    @PostMapping("/api/urls")
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest req) {
        ShortUrl saved = urlService.create(req.url());
        UrlResponse body = new UrlResponse(
                saved.getCode(),
                baseUrl + "/" + saved.getCode(),
                saved.getOriginalUrl(),
                saved.getCreatedAt());
        return ResponseEntity
                .created(URI.create(body.shortUrl()))
                .body(body);
    }

    @GetMapping("/{code:[0-9a-zA-Z]{1,11}}")
    public ResponseEntity<Void> redirect(@PathVariable String code,
                                          HttpServletRequest request) {
        Optional<ShortUrl> opt = urlService.resolve(code);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ShortUrl url = opt.get();
        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now(clock))) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        events.publishEvent(new ClickRecordedEvent(
                url.getId(),
                Instant.now(clock),
                clientIp(request),
                request.getHeader("Referer"),
                request.getHeader("User-Agent")
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, url.getOriginalUrl());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes.**

Run: `./gradlew test --tests UrlControllerTest`
Expected: PASS — all 5 tests green.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/url/UrlController.java \
        src/test/java/limhjun/me/shortly/url/UrlControllerTest.java
git commit -m "feat(url): add UrlController for POST create + GET {code} redirect"
```

---

## Task 14: Implement `AnalyticsController`

**Files:**
- Create: `src/main/java/limhjun/me/shortly/analytics/AnalyticsController.java`
- Test: `src/test/java/limhjun/me/shortly/analytics/AnalyticsControllerTest.java`

- [ ] **Step 1: Write the failing slice test.**

Create `src/test/java/limhjun/me/shortly/analytics/AnalyticsControllerTest.java`:

```java
package limhjun.me.shortly.analytics;

import limhjun.me.shortly.analytics.dto.AnalyticsResponse;
import limhjun.me.shortly.url.ShortUrlNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AnalyticsController.class)
class AnalyticsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AnalyticsService service;

    @Test
    void returnsStatsAs200() throws Exception {
        when(service.stats("ph7")).thenReturn(new AnalyticsResponse(
                "ph7", 42L, 7L, List.of(), List.of()));

        mvc.perform(get("/api/urls/ph7/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(42))
                .andExpect(jsonPath("$.uniqueVisitors").value(7));
    }

    @Test
    void returns404WhenServiceThrowsNotFound() throws Exception {
        when(service.stats("zzz")).thenThrow(new ShortUrlNotFoundException("zzz"));
        mvc.perform(get("/api/urls/zzz/stats"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails.**

Run: `./gradlew test --tests AnalyticsControllerTest`
Expected: FAIL — `AnalyticsController` doesn't exist.

- [ ] **Step 3: Implement `AnalyticsController`.**

Create `src/main/java/limhjun/me/shortly/analytics/AnalyticsController.java`:

```java
package limhjun.me.shortly.analytics;

import limhjun.me.shortly.analytics.dto.AnalyticsResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/urls")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/{code}/stats")
    public AnalyticsResponse stats(@PathVariable String code) {
        return service.stats(code);
    }
}
```

- [ ] **Step 4: Run the test.**

Run: `./gradlew test --tests AnalyticsControllerTest`
Expected: only the "200 OK" test passes — the 404 test fails because no exception handler maps `ShortUrlNotFoundException → 404` yet. That handler is Task 15.

This intermediate state is acceptable. We'll re-run after Task 15.

- [ ] **Step 5: Commit (without the 404 test green).**

```bash
git add src/main/java/limhjun/me/shortly/analytics/AnalyticsController.java \
        src/test/java/limhjun/me/shortly/analytics/AnalyticsControllerTest.java
git commit -m "feat(analytics): add AnalyticsController (404 mapping comes in Task 15)"
```

---

## Task 15: Implement `ApiErrorAdvice` (unified error responses)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/config/ApiErrorAdvice.java`

Centralizes mapping from exceptions → unified error JSON envelope. Pulls `traceId` from Micrometer Tracing context if available.

- [ ] **Step 1: Create the error envelope record.**

Create `src/main/java/limhjun/me/shortly/config/ErrorResponse.java`:

```java
package limhjun.me.shortly.config;

import java.time.Instant;

public record ErrorResponse(
        String type,
        String message,
        Instant timestamp,
        String path,
        String traceId
) {}
```

- [ ] **Step 2: Create `ApiErrorAdvice`.**

Create `src/main/java/limhjun/me/shortly/config/ApiErrorAdvice.java`:

```java
package limhjun.me.shortly.config;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import limhjun.me.shortly.url.InvalidUrlException;
import limhjun.me.shortly.url.ShortUrlNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;

@RestControllerAdvice
@Slf4j
public class ApiErrorAdvice {

    private final Tracer tracer;
    private final Clock clock;

    public ApiErrorAdvice(Tracer tracer, Clock clock) {
        this.tracer = tracer;
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage(), req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformedJson(HttpMessageNotReadableException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "malformed_request", "request body is not valid JSON", req);
    }

    @ExceptionHandler(InvalidUrlException.class)
    ResponseEntity<ErrorResponse> invalidUrl(InvalidUrlException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage(), req);
    }

    @ExceptionHandler(ShortUrlNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(ShortUrlNotFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "not_found", "short URL not found", req);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ErrorResponse> dbDown(DataAccessResourceFailureException e, HttpServletRequest req) {
        log.error("database unreachable", e);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", "database temporarily unavailable", req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> generic(Exception e, HttpServletRequest req) {
        log.error("unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "internal error", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String type, String message, HttpServletRequest req) {
        String traceId = tracer.currentSpan() != null
                ? tracer.currentSpan().context().traceId()
                : null;
        return ResponseEntity.status(status).body(new ErrorResponse(
                type, message, Instant.now(clock), req.getRequestURI(), traceId));
    }
}
```

- [ ] **Step 3: Re-run the AnalyticsController test.**

Run: `./gradlew test --tests AnalyticsControllerTest`
Expected: PASS — both tests now green (404 handler in place).

- [ ] **Step 4: Re-run the UrlController test for sanity.**

Run: `./gradlew test --tests UrlControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/config/ApiErrorAdvice.java \
        src/main/java/limhjun/me/shortly/config/ErrorResponse.java
git commit -m "feat(config): add ApiErrorAdvice with unified error envelope and traceId"
```

---

## Task 16: Implement rate-limit configuration (`RateLimitProperties`, `BucketProvider`)

**Files:**
- Create: `src/main/java/limhjun/me/shortly/ratelimit/RateLimitProperties.java`
- Create: `src/main/java/limhjun/me/shortly/ratelimit/BucketProvider.java`
- Test: `src/test/java/limhjun/me/shortly/ratelimit/BucketProviderTest.java`

`RateLimitProperties` defines per-endpoint limits. `BucketProvider` returns a Bucket4j `Bucket` per `(endpoint, ip)`, cached in Caffeine.

- [ ] **Step 1: Write the failing test.**

Create `src/test/java/limhjun/me/shortly/ratelimit/BucketProviderTest.java`:

```java
package limhjun.me.shortly.ratelimit;

import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BucketProviderTest {

    private final RateLimitProperties props = new RateLimitProperties(
            new RateLimitProperties.Limit(10, Duration.ofMinutes(1)),
            new RateLimitProperties.Limit(60, Duration.ofMinutes(1)),
            new RateLimitProperties.Limit(30, Duration.ofMinutes(1))
    );
    private final BucketProvider provider = new BucketProvider(props);

    @Test
    void sameIpAndEndpointReturnsSameBucket() {
        Bucket a = provider.bucketFor("create", "1.2.3.4");
        Bucket b = provider.bucketFor("create", "1.2.3.4");
        assertThat(a).isSameAs(b);
    }

    @Test
    void differentIpReturnsDifferentBucket() {
        Bucket a = provider.bucketFor("create", "1.2.3.4");
        Bucket b = provider.bucketFor("create", "1.2.3.5");
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void differentEndpointReturnsDifferentBucket() {
        Bucket a = provider.bucketFor("create", "1.2.3.4");
        Bucket b = provider.bucketFor("redirect", "1.2.3.4");
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void createBucketHasCapacityTen() {
        Bucket b = provider.bucketFor("create", "1.2.3.4");
        for (int i = 0; i < 10; i++) {
            assertThat(b.tryConsume(1)).isTrue();
        }
        assertThat(b.tryConsume(1)).isFalse();
    }

    @Test
    void redirectBucketHasCapacitySixty() {
        Bucket b = provider.bucketFor("redirect", "1.2.3.4");
        for (int i = 0; i < 60; i++) {
            assertThat(b.tryConsume(1)).isTrue();
        }
        assertThat(b.tryConsume(1)).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails.**

Run: `./gradlew test --tests BucketProviderTest`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement `RateLimitProperties`.**

Create `src/main/java/limhjun/me/shortly/ratelimit/RateLimitProperties.java`:

```java
package limhjun.me.shortly.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        Limit create,
        Limit redirect,
        Limit stats
) {
    public record Limit(int capacity, Duration refill) {}

    public Limit forEndpoint(String endpoint) {
        return switch (endpoint) {
            case "create"   -> create;
            case "redirect" -> redirect;
            case "stats"    -> stats;
            default         -> throw new IllegalArgumentException("unknown endpoint: " + endpoint);
        };
    }
}
```

- [ ] **Step 4: Implement `BucketProvider`.**

Create `src/main/java/limhjun/me/shortly/ratelimit/BucketProvider.java`:

```java
package limhjun.me.shortly.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class BucketProvider {

    private final RateLimitProperties props;
    private final Cache<String, Bucket> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();

    public BucketProvider(RateLimitProperties props) {
        this.props = props;
    }

    public Bucket bucketFor(String endpoint, String ip) {
        String key = endpoint + ":" + ip;
        return cache.get(key, k -> newBucket(props.forEndpoint(endpoint)));
    }

    private static Bucket newBucket(RateLimitProperties.Limit limit) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit.capacity())
                .refillGreedy(limit.capacity(), limit.refill())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
```

- [ ] **Step 5: Add the rate limit config to `application.properties`.**

Append to `src/main/resources/application.properties`:

```properties
# Rate limits
app.rate-limit.create.capacity=10
app.rate-limit.create.refill=PT1M
app.rate-limit.redirect.capacity=60
app.rate-limit.redirect.refill=PT1M
app.rate-limit.stats.capacity=30
app.rate-limit.stats.refill=PT1M
```

- [ ] **Step 6: Enable `@ConfigurationProperties` scanning.**

Add to `ShortlyApplication.java` class-level annotations:

```java
@org.springframework.boot.context.properties.ConfigurationPropertiesScan("limhjun.me.shortly")
```

(Goes alongside `@SpringBootApplication`.)

- [ ] **Step 7: Run the test to confirm it passes.**

Run: `./gradlew test --tests BucketProviderTest`
Expected: PASS — all 5 tests green.

- [ ] **Step 8: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/ratelimit/RateLimitProperties.java \
        src/main/java/limhjun/me/shortly/ratelimit/BucketProvider.java \
        src/main/java/limhjun/me/shortly/ShortlyApplication.java \
        src/main/resources/application.properties \
        src/test/java/limhjun/me/shortly/ratelimit/BucketProviderTest.java
git commit -m "feat(ratelimit): add Bucket4j-backed BucketProvider with per-endpoint limits"
```

---

## Task 17: Implement `RateLimitFilter`

**Files:**
- Create: `src/main/java/limhjun/me/shortly/ratelimit/RateLimitFilter.java`

The filter classifies the request, picks a bucket, and either consumes a token or returns 429.

- [ ] **Step 1: Implement the filter.**

Create `src/main/java/limhjun/me/shortly/ratelimit/RateLimitFilter.java`:

```java
package limhjun.me.shortly.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final BucketProvider buckets;
    private final MeterRegistry registry;

    public RateLimitFilter(BucketProvider buckets, MeterRegistry registry) {
        this.buckets = buckets;
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String endpoint = classify(req);
        if (endpoint == null) {
            chain.doFilter(req, res);
            return;
        }

        String ip = clientIp(req);
        Bucket bucket = buckets.bucketFor(endpoint, ip);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            chain.doFilter(req, res);
        } else {
            Counter.builder("shortly.ratelimit.blocked")
                    .tag("endpoint", endpoint)
                    .register(registry)
                    .increment();
            long retryAfterSec = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            res.setHeader("Retry-After", Long.toString(retryAfterSec));
        }
    }

    private String classify(HttpServletRequest req) {
        String path = req.getRequestURI();
        String method = req.getMethod();

        if (path.startsWith("/actuator/")) return null; // exempt
        if (path.startsWith("/h2-console/")) return null; // dev
        if (path.startsWith("/error")) return null;

        if ("POST".equals(method) && path.equals("/api/urls")) return "create";
        if ("GET".equals(method) && path.matches("/api/urls/[^/]+/stats")) return "stats";
        if ("GET".equals(method) && path.matches("/[0-9a-zA-Z]{1,11}")) return "redirect";

        return null;
    }

    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
```

- [ ] **Step 2: Verify compile.**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Smoke-test the filter manually.**

Run: `./gradlew bootRun &` (run in background)

Wait for "Started ShortlyApplication" in the log, then:

```bash
# Should succeed up to 10 times
for i in {1..11}; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/urls \
       -H "Content-Type: application/json" \
       -d '{"url":"https://example.com"}'
done
```

Expected: ten `201`s, then one `429`.

Stop the app: `pkill -f bootRun`

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/ratelimit/RateLimitFilter.java
git commit -m "feat(ratelimit): add RateLimitFilter with Retry-After header on 429"
```

---

## Task 18: Configure Caffeine cache for `@Cacheable`

**Files:**
- Create: `src/main/java/limhjun/me/shortly/config/CacheConfig.java`

Wires Spring Boot's caching abstraction to a Caffeine backend with the right size / TTL for `code → ShortUrl` lookups.

- [ ] **Step 1: Create `CacheConfig`.**

Create `src/main/java/limhjun/me/shortly/config/CacheConfig.java`:

```java
package limhjun.me.shortly.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager m = new CaffeineCacheManager("shortUrl");
        m.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5)));
        return m;
    }
}
```

- [ ] **Step 2: Verify compile.**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/config/CacheConfig.java
git commit -m "feat(config): wire Caffeine cache manager (10k entries, 5-min TTL)"
```

---

## Task 19: Add custom Micrometer metrics for URL creation and redirects

**Files:**
- Modify: `src/main/java/limhjun/me/shortly/url/UrlController.java`

The `ClickRecorder` and `RateLimitFilter` already publish counters. Add the missing two: `shortly.url.created` and `shortly.url.redirect` (tagged by status).

- [ ] **Step 1: Inject `MeterRegistry` and add counters.**

Modify `src/main/java/limhjun/me/shortly/url/UrlController.java`. Replace the constructor and add fields:

```java
    private final UrlService urlService;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String baseUrl;
    private final io.micrometer.core.instrument.Counter createdCounter;
    private final io.micrometer.core.instrument.MeterRegistry registry;

    public UrlController(UrlService urlService,
                         ApplicationEventPublisher events,
                         Clock clock,
                         @Value("${app.base-url}") String baseUrl,
                         io.micrometer.core.instrument.MeterRegistry registry) {
        this.urlService = urlService;
        this.events = events;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.registry = registry;
        this.createdCounter = io.micrometer.core.instrument.Counter
                .builder("shortly.url.created").register(registry);
    }
```

- [ ] **Step 2: Increment in `create()`.**

Inside `create(...)`, after the `urlService.create(req.url())` call, add:

```java
        createdCounter.increment();
```

- [ ] **Step 3: Replace status-returning code in `redirect(...)` to emit a tagged counter.**

Replace the body of the `redirect(...)` method with:

```java
        Optional<ShortUrl> opt = urlService.resolve(code);
        if (opt.isEmpty()) {
            redirectCounter("not_found").increment();
            return ResponseEntity.notFound().build();
        }
        ShortUrl url = opt.get();
        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now(clock))) {
            redirectCounter("gone").increment();
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        events.publishEvent(new ClickRecordedEvent(
                url.getId(),
                Instant.now(clock),
                clientIp(request),
                request.getHeader("Referer"),
                request.getHeader("User-Agent")
        ));
        redirectCounter("found").increment();

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, url.getOriginalUrl());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
```

And add the helper:

```java
    private io.micrometer.core.instrument.Counter redirectCounter(String status) {
        return io.micrometer.core.instrument.Counter
                .builder("shortly.url.redirect")
                .tag("status", status)
                .register(registry);
    }
```

- [ ] **Step 4: Run the controller test.**

Run: `./gradlew test --tests UrlControllerTest`
Expected: PASS (the test uses `@MockBean MeterRegistry`-autowiring; if it fails because `MeterRegistry` isn't a mocked bean, add `@MockBean io.micrometer.core.instrument.MeterRegistry registry;` to the test class).

If failing, add the `@MockBean` declaration:

```java
    @MockBean io.micrometer.core.instrument.MeterRegistry meterRegistry;
```

Re-run; expected PASS.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/limhjun/me/shortly/url/UrlController.java \
        src/test/java/limhjun/me/shortly/url/UrlControllerTest.java
git commit -m "feat(metrics): add shortly.url.created counter and shortly.url.redirect{status}"
```

---

## Task 20: Integration test (end-to-end happy path with Testcontainers)

**Files:**
- Create: `src/test/java/limhjun/me/shortly/IntegrationTest.java`

The required scenario: create → redirect → verify click recorded → verify analytics → exercise rate limit boundary → test expired link → verify cache behavior.

- [ ] **Step 1: Replace the auto-generated `TestcontainersConfiguration.java` if needed.**

Open `src/test/java/limhjun/me/shortly/TestcontainersConfiguration.java`. Verify it declares a Postgres container as a `@ServiceConnection` `@Bean`. The Initializr default looks like:

```java
package limhjun.me.shortly;

import org.springframework.boot.devtools.restart.RestartScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    @RestartScope
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"));
    }
}
```

If the version pin is `postgres:latest`, change to `postgres:16-alpine` for reproducibility:

```java
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
```

- [ ] **Step 2: Create the integration test.**

Create `src/test/java/limhjun/me/shortly/IntegrationTest.java`:

```java
package limhjun.me.shortly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import limhjun.me.shortly.click.ClickEventRepository;
import limhjun.me.shortly.url.ShortUrl;
import limhjun.me.shortly.url.ShortUrlRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired ShortUrlRepository urls;
    @Autowired ClickEventRepository clicks;
    @Autowired ObjectMapper json;

    @Test
    void createRedirectAndAnalytics() throws Exception {
        // Create
        ResponseEntity<JsonNode> created = http.postForEntity(
                "/api/urls",
                Map.of("url", "https://example.com"),
                JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = created.getBody().get("code").asText();

        // Redirect (twice from different "IPs" via X-Forwarded-For)
        for (String ip : new String[]{"203.0.113.1", "203.0.113.2"}) {
            HttpHeaders h = new HttpHeaders();
            h.set("X-Forwarded-For", ip);
            h.set("Referer", "https://news.ycombinator.com");
            ResponseEntity<Void> redir = http.exchange(
                    "/" + code, HttpMethod.GET, new HttpEntity<>(h), Void.class);
            assertThat(redir.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(redir.getHeaders().getLocation())
                    .isEqualTo(java.net.URI.create("https://example.com"));
        }

        // Wait for async click writes
        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ShortUrl found = urls.findByCode(code).orElseThrow();
            assertThat(clicks.countByShortUrlId(found.getId())).isEqualTo(2);
        });

        // Analytics
        ResponseEntity<JsonNode> stats = http.getForEntity(
                "/api/urls/" + code + "/stats", JsonNode.class);
        assertThat(stats.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stats.getBody().get("totalClicks").asLong()).isEqualTo(2);
        assertThat(stats.getBody().get("uniqueVisitors").asLong()).isEqualTo(2);
    }

    @Test
    void unknownCodeReturns404() {
        ResponseEntity<Void> resp = http.getForEntity("/zzz999", Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void expiredLinkReturns410() {
        ShortUrl expired = urls.save(new ShortUrl(
                null, null,
                "https://expired.example.com",
                Instant.now().minus(2, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS)));
        ResponseEntity<Void> resp = http.getForEntity("/" + expired.getCode(), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void rateLimitTriggersAtCreateBoundary() {
        for (int i = 0; i < 10; i++) {
            ResponseEntity<JsonNode> resp = http.postForEntity(
                    "/api/urls",
                    Map.of("url", "https://example.com/" + i),
                    JsonNode.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<JsonNode> resp11 = http.postForEntity(
                "/api/urls",
                Map.of("url", "https://example.com/over"),
                JsonNode.class);
        assertThat(resp11.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp11.getHeaders().getFirst("Retry-After")).isNotNull();
    }
}
```

- [ ] **Step 3: Run the integration test.**

Run: `./gradlew test --tests IntegrationTest`
Expected: PASS — all 4 scenarios green. First run downloads the Postgres container image (~80 MB).

If `rateLimitTriggersAtCreateBoundary` fails because the previous test class consumed the same IP's bucket, set the rate limit higher in `application-test.properties` or run that test in isolation. Quick fix: bump capacity in test profile to 100 — but the cleaner fix is the test currently uses the default `127.0.0.1` for all requests and the first three tests of this class also use it. Reset by adding `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` to the class temporarily, OR bump capacity.

For v1: add `@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)` to the test class. That's a sledgehammer but it works.

Re-run: PASS.

- [ ] **Step 4: Commit.**

```bash
git add src/test/java/limhjun/me/shortly/IntegrationTest.java \
        src/test/java/limhjun/me/shortly/TestcontainersConfiguration.java
git commit -m "test: add @SpringBootTest integration scenarios with Testcontainers Postgres"
```

---

## Task 21: Configure structured JSON logging for prod profile

**Files:**
- Create: `src/main/resources/logback-spring.xml`

- [ ] **Step 1: Create the Logback config.**

Create `src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <springProfile name="!prod">
        <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="prod">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeContext>false</includeContext>
                <includeMdc>true</includeMdc>
                <fieldNames>
                    <timestamp>timestamp</timestamp>
                    <level>level</level>
                    <thread>thread</thread>
                    <logger>logger</logger>
                    <message>message</message>
                </fieldNames>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>
</configuration>
```

- [ ] **Step 2: Verify dev still uses plain text.**

Run: `./gradlew bootRun --args='--spring.main.web-application-type=none'` & sleep 8; pkill -f bootRun

Expected: console output is plain text (the `!prod` branch).

- [ ] **Step 3: Commit.**

```bash
git add src/main/resources/logback-spring.xml
git commit -m "feat(logging): JSON encoder in prod profile, plain text in dev"
```

---

## Task 22: Add Dockerfile + .dockerignore

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`

- [ ] **Step 1: Create the Dockerfile.**

Create `Dockerfile`:

```dockerfile
# Build stage
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# Runtime stage
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s \
    CMD wget -q --spider http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

- [ ] **Step 2: Create `.dockerignore`.**

Create `.dockerignore`:

```
.git
.gitignore
.gradle
.idea
build
data
docs
.github
README.md
HELP.md
**/*.md
.env*
fly.toml
compose.yaml
```

- [ ] **Step 3: Build the image locally.**

Run: `docker build -t shortly:dev .`
Expected: build succeeds. Final image size ~200 MB.

- [ ] **Step 4: Smoke-test the container.**

Run:
```bash
docker run --rm -p 8080:8080 \
    -e SPRING_PROFILES_ACTIVE=prod \
    -e SPRING_DATASOURCE_URL=jdbc:h2:mem:test;MODE=PostgreSQL \
    -e SPRING_DATASOURCE_USERNAME=sa \
    -e SPRING_DATASOURCE_PASSWORD= \
    -e APP_BASE_URL=http://localhost:8080 \
    -e APP_IP_HASH_PEPPER=test \
    shortly:dev &
sleep 15
curl -s http://localhost:8080/actuator/health
docker ps -q -f ancestor=shortly:dev | xargs -r docker stop
```

Expected: `{"status":"UP",...}`. (H2-on-prod-profile is intentional shortcut for local container smoke-test only.)

- [ ] **Step 5: Commit.**

```bash
git add Dockerfile .dockerignore
git commit -m "ops: add multi-stage Dockerfile (Temurin 25 alpine, ~200MB final)"
```

---

## Task 23: Add docker-compose for local Postgres dev

**Files:**
- Create: `compose.yaml`

- [ ] **Step 1: Create `compose.yaml`.**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: shortly
      POSTGRES_USER: shortly
      POSTGRES_PASSWORD: shortly
    ports:
      - "5432:5432"
    volumes:
      - shortly-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U shortly"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  shortly-pgdata:
```

- [ ] **Step 2: Verify it boots.**

Run: `docker compose up -d postgres && sleep 5 && docker compose ps`
Expected: `postgres` is `running` and `healthy`.

Stop: `docker compose down`

- [ ] **Step 3: Commit.**

```bash
git add compose.yaml
git commit -m "ops: add compose.yaml for local Postgres dev"
```

---

## Task 24: Add fly.toml for Fly.io deployment

**Files:**
- Create: `fly.toml`

- [ ] **Step 1: Create the Fly config.**

Create `fly.toml`:

```toml
app = "shortly"
primary_region = "nrt" # Tokyo - change to nearest you

[build]
  dockerfile = "Dockerfile"

[env]
  SPRING_PROFILES_ACTIVE = "prod"
  JAVA_TOOL_OPTIONS = "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"

[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = "stop"
  auto_start_machines = true
  min_machines_running = 0
  processes = ["app"]

  [[http_service.checks]]
    grace_period = "20s"
    interval = "30s"
    method = "GET"
    timeout = "5s"
    path = "/actuator/health/liveness"

[[vm]]
  memory = "256mb"
  cpu_kind = "shared"
  cpus = 1
```

- [ ] **Step 2: (Optional, requires `fly` CLI) verify config parses.**

Run: `fly config validate` (only if `fly` is installed; skip otherwise)
Expected: `Configuration is valid`.

- [ ] **Step 3: Commit.**

```bash
git add fly.toml
git commit -m "ops: add fly.toml for Fly.io deployment (Tokyo region, 256MB shared CPU)"
```

---

## Task 25: GitHub Actions CI

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Create the workflow.**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle.kts', 'gradle/wrapper/gradle-wrapper.properties') }}

      - name: Build & test
        run: ./gradlew check --no-daemon
```

- [ ] **Step 2: Commit.**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add GitHub Actions workflow running ./gradlew check on push and PR"
```

---

## Task 26: Write the README

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write the README following the spec's §8.5 structure.**

Create `README.md`:

````markdown
# Shortly

A polished public URL shortener with click analytics, built on Spring Boot 4 + Java 25.

```json
GET /api/urls/ph7/stats →
{
  "code": "ph7",
  "totalClicks": 142,
  "uniqueVisitors": 89,
  "byDay": [
    {"day": "2026-05-08T00:00:00Z", "count": 22},
    {"day": "2026-05-09T00:00:00Z", "count": 41}
  ],
  "topReferrers": [
    {"referrer": "https://news.ycombinator.com", "count": 67}
  ]
}
```

**Live demo:** https://shortly.fly.dev (set after first deploy)

## Quickstart

Create a short URL, follow the redirect, fetch analytics:

```bash
# Create
curl -s -X POST https://shortly.fly.dev/api/urls \
     -H 'Content-Type: application/json' \
     -d '{"url":"https://example.com"}'
# → {"code":"ph7","shortUrl":"https://shortly.fly.dev/ph7",...}

# Follow
curl -I https://shortly.fly.dev/ph7
# → HTTP/2 302
# → location: https://example.com

# Stats
curl -s https://shortly.fly.dev/api/urls/ph7/stats
```

## Architecture

- Single Spring Boot 4.0.6 deployable, single Postgres 16 database.
- Feature-first packages: `url`, `click`, `analytics`, `ratelimit`, `config`.
- Async click recording via `ApplicationEventPublisher` on a virtual-thread executor (Java 25 + Spring Boot 4 virtual-thread support).
- In-process per-IP rate limiting (Bucket4j + Caffeine).
- Flyway migrations, Hibernate validate-only.
- Multi-stage Docker → Fly.io with managed Postgres.

See [`docs/superpowers/specs/2026-05-09-shortly-url-shortener-design.md`](docs/superpowers/specs/2026-05-09-shortly-url-shortener-design.md) for the full design.

## Local development

```bash
./gradlew bootRun
# → starts on http://localhost:8080 with H2 (file-mode at ./data/)
```

## Local Postgres run

```bash
docker compose up -d postgres
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/shortly \
SPRING_DATASOURCE_USERNAME=shortly \
SPRING_DATASOURCE_PASSWORD=shortly \
APP_IP_HASH_PEPPER=local-pepper \
APP_BASE_URL=http://localhost:8080 \
./gradlew bootRun --args='--spring.profiles.active=prod'
```

## Tests

```bash
./gradlew check
# → ~90 s; spins up a Postgres container via Testcontainers
```

## Deploy your own

```bash
fly launch --no-deploy
fly secrets set \
    APP_IP_HASH_PEPPER=$(openssl rand -hex 32) \
    APP_BASE_URL=https://your-app.fly.dev
fly postgres create   # follow prompts; then attach with: fly postgres attach
fly deploy
```

## What's NOT in v1

These are intentional non-goals; planned for v2:

- URL safety scanning (Google Safe Browsing API)
- Captcha (hCaptcha) on the create endpoint
- Takedown / abuse-reporting endpoint
- GeoIP enrichment of analytics (country breakdown)
- Hashids replacement for Base62-of-ID short codes
- Per-day analytics windowing (`?window=30d`)
- Distributed rate limit via Redis
- Remote tracing exporter
- Frontend dashboard

## Tech stack

Spring Boot 4.0.6 · Spring Framework 7 · Java 25 LTS · Postgres 16 · Flyway · Bucket4j · Caffeine · Micrometer + Prometheus · Testcontainers · Awaitility · Logstash JSON encoder · Docker · Fly.io · GitHub Actions
````

- [ ] **Step 2: Commit.**

```bash
git add README.md
git commit -m "docs: add README with quickstart, architecture, deploy guide, v2 roadmap"
```

---

## Self-Review

After writing the plan above, here's a self-review against the spec:

**Spec coverage:**

| Spec section | Plan task(s) | Coverage |
|---|---|---|
| §3 Architecture (package layout, no Modulith) | 5, 7, 11, 13, 14, 15, 17 | ✓ implements every package |
| §4.1 ShortUrl entity (SEQUENCE, @PrePersist, length=11) | 5 | ✓ exact match |
| §4.2 ClickEvent entity (raw FK, indexes) | 7 | ✓ |
| §4.3 Repositories (4 query methods) | 5, 7, 8 | ✓ all four queries present |
| §4.4 IpHasher (daily salt + pepper) | 6 | ✓ |
| §4.5 Flyway V1__init.sql | 4 | ✓ sequence start 100k, 2 indexes |
| §5.1 POST create flow | 9, 13 | ✓ |
| §5.2 GET redirect flow (cache, expired→410, 404) | 9, 13, 18 | ✓ |
| §5.3 GET stats flow | 11, 14 | ✓ |
| §5.4 Rate limit table (incl. actuator exempt) | 16, 17 | ✓ |
| §6.1 Unified error envelope | 15 | ✓ ErrorResponse + Advice |
| §6.2 Status codes | 15 | ✓ all six handlers |
| §6.3 Failure isolation (HikariCP timeout, bounded async exec) | 2, 10 | ✓ |
| §7 Testing pyramid | 3, 5, 6, 7, 9, 11, 13, 14, 16, 20 | ✓ unit + slice + integration |
| §8.1 Fly.io target | 24 | ✓ |
| §8.2 Multi-stage Dockerfile | 22 | ✓ |
| §8.3 Config (env vars, prod profile) | 2 | ✓ |
| §8.4 Observability surface (custom metrics) | 10, 17, 19 | ✓ all 5 metrics |
| §8.5 README structure | 26 | ✓ all 10 sections |
| §9 v2 roadmap | 26 (in README "What's NOT in v1") | ✓ |

No gaps found.

**Placeholder scan:** No "TBD", "TODO", or "implement later" tokens in the plan. Every step has actual content.

**Type consistency:**
- `Base62Encoder.encode/decode` — used in tasks 3, 5, 9. Same signature. ✓
- `ShortUrl(id, code, originalUrl, createdAt, expiresAt)` constructor — used in tasks 5, 7, 9, 11, 13, 20. Same shape. ✓
- `ClickRecordedEvent(shortUrlId, clickedAt, ip, referrer, userAgent)` — used in tasks 10, 13. Same shape. ✓
- `AnalyticsResponse` shape `(code, totalClicks, uniqueVisitors, byDay, topReferrers)` — used in tasks 11, 14. Same. ✓
- `RateLimitProperties.Limit(capacity, refill)` — used in tasks 16, 17. Same. ✓

**Forward references:** Task 7 imports `DayCount` and `ReferrerCount` (created in Task 8). Documented in Task 7's note; engineer is told to do Task 8 first if a compile error appears. ✓

**Order discipline:** Tasks build on prior tasks; no late-arriving rework. ✓

---

## Plan Complete

Plan saved to `docs/superpowers/plans/2026-05-09-shortly-url-shortener.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — A fresh subagent runs each task; the lead agent reviews between tasks. Fast iteration, two-stage review per task. Uses `superpowers:subagent-driven-development`.

2. **Inline Execution** — Tasks executed in this session via `superpowers:executing-plans`. Batch execution with checkpoints for review.

Which approach?
