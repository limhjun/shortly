package limhjun.me.shortly.url;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import limhjun.me.shortly.click.ClickRecordedEvent;
import limhjun.me.shortly.ratelimit.RateLimitFilter;
import limhjun.me.shortly.url.dto.CreateUrlRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UrlController.class)
@Import(UrlControllerTest.TestConfig.class)
@TestPropertySource(properties = {
        "app.base-url=http://localhost:8080"
})
class UrlControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ClickEventCaptor clickEventCaptor() {
            return new ClickEventCaptor();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        RateLimitFilter rateLimitFilter() {
            // Create a spy and make it pass-through for testing
            var filter = new RateLimitFilter(null, null) {
                @Override
                protected void doFilterInternal(
                        jakarta.servlet.http.HttpServletRequest req,
                        jakarta.servlet.http.HttpServletResponse res,
                        FilterChain chain)
                        throws jakarta.servlet.ServletException, java.io.IOException {
                    // Just pass through without rate limiting
                    chain.doFilter(req, res);
                }
            };
            return filter;
        }
    }

    @Component
    static class ClickEventCaptor {
        final List<ClickRecordedEvent> captured = new ArrayList<>();

        @EventListener
        void on(ClickRecordedEvent e) {
            captured.add(e);
        }

        void reset() { captured.clear(); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ClickEventCaptor captor;

    @MockitoBean UrlService urlService;
    @MockitoBean Clock clock;

    @BeforeEach
    void setUp() {
        captor.reset();
        // Fixed "now" that is after any expired test fixture's expiresAt (2026-05-02)
        Instant now = Instant.parse("2026-05-09T10:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

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

        assertThat(captor.captured).hasSize(1);
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

        assertThat(captor.captured).isEmpty();
    }
}
