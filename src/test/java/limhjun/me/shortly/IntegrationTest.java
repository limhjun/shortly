package limhjun.me.shortly;

import tools.jackson.databind.JsonNode;
import limhjun.me.shortly.click.ClickEventRepository;
import limhjun.me.shortly.url.ShortUrl;
import limhjun.me.shortly.url.ShortUrlRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.baseline-on-migrate=false"
)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class IntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired ShortUrlRepository urls;
    @Autowired ClickEventRepository clicks;

    @Test
    void createRedirectAndAnalytics() throws Exception {
        // Create
        ResponseEntity<JsonNode> created = http.postForEntity(
                "/api/urls",
                Map.of("url", "https://example.com"),
                JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = created.getBody().get("code").asText();

        // Redirect (twice from different IPs via X-Forwarded-For)
        // Use a non-redirecting client so we can assert on the 302 response directly.
        TestRestTemplate noRedirect = http.withRedirects(HttpRedirects.DONT_FOLLOW);
        for (String ip : new String[]{"203.0.113.1", "203.0.113.2"}) {
            HttpHeaders h = new HttpHeaders();
            h.set("X-Forwarded-For", ip);
            h.set("Referer", "https://news.ycombinator.com");
            ResponseEntity<Void> redir = noRedirect.exchange(
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
