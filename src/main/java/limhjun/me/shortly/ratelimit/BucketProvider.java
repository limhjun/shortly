package limhjun.me.shortly.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class BucketProvider {

    private final RateLimitProperties props;
    private final Cache<String, Bucket> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();

    public BucketProvider(RateLimitProperties props) {
        this.props = props;
    }

    public Bucket bucketFor(String endpoint, String ip) {
        String key = endpoint + ":" + ip;
        return cache.get(key, k -> newBucket(props.forEndpoint(endpoint)));
    }

    private static Bucket newBucket(RateLimitProperties.Limit limit) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit.capacity())
                .refillGreedy(limit.capacity(), limit.refill())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
