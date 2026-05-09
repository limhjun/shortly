package limhjun.me.shortly.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final BucketProvider buckets;
    private final MeterRegistry registry;

    public RateLimitFilter(BucketProvider buckets, MeterRegistry registry) {
        this.buckets = buckets;
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String endpoint = classify(req);
        if (endpoint == null) {
            chain.doFilter(req, res);
            return;
        }

        String ip = clientIp(req);
        Bucket bucket = buckets.bucketFor(endpoint, ip);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            chain.doFilter(req, res);
        } else {
            Counter.builder("shortly.ratelimit.blocked")
                    .tag("endpoint", endpoint)
                    .register(registry)
                    .increment();
            long retryAfterSec = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            res.setHeader("Retry-After", Long.toString(retryAfterSec));
        }
    }

    private String classify(HttpServletRequest req) {
        String path = req.getRequestURI();
        String method = req.getMethod();

        if (path.startsWith("/actuator/")) return null; // exempt
        if (path.startsWith("/h2-console/")) return null; // dev
        if (path.startsWith("/error")) return null;

        if ("POST".equals(method) && path.equals("/api/urls")) return "create";
        if ("GET".equals(method) && path.matches("/api/urls/[^/]+/stats")) return "stats";
        if ("GET".equals(method) && path.matches("/[0-9a-zA-Z]{1,11}")) return "redirect";

        return null;
    }

    private String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
