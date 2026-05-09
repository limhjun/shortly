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
