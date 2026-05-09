package limhjun.me.shortly.url.dto;

import java.time.Instant;

public record UrlResponse(
        String code,
        String shortUrl,
        String originalUrl,
        Instant createdAt
) {}
