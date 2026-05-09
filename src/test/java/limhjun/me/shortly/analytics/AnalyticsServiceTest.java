package limhjun.me.shortly.analytics;

import limhjun.me.shortly.analytics.dto.AnalyticsResponse;
import limhjun.me.shortly.click.ClickEventRepository;
import limhjun.me.shortly.url.ShortUrl;
import limhjun.me.shortly.url.ShortUrlNotFoundException;
import limhjun.me.shortly.url.ShortUrlRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
