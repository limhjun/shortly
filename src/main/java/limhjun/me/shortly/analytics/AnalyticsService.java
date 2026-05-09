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
