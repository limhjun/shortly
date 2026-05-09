package limhjun.me.shortly.config;

import java.time.Instant;

public record ErrorResponse(
        String type,
        String message,
        Instant timestamp,
        String path,
        String traceId
) {}
