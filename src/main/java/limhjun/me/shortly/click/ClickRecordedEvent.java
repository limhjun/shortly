package limhjun.me.shortly.click;

import java.time.Instant;

public record ClickRecordedEvent(
        Long shortUrlId,
        Instant clickedAt,
        String ip,
        String referrer,
        String userAgent
) {}
