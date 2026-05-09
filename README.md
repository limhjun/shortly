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

**Live demo:** _set after first deploy_

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

If H2 startup fails with a migration error, delete the cached H2 file and retry:

```bash
rm -rf ./data
./gradlew bootRun
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
