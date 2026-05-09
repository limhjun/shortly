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
