package limhjun.me.shortly.ratelimit;

import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BucketProviderTest {

    private final RateLimitProperties props = new RateLimitProperties(
            new RateLimitProperties.Limit(10, Duration.ofMinutes(1)),
            new RateLimitProperties.Limit(60, Duration.ofMinutes(1)),
            new RateLimitProperties.Limit(30, Duration.ofMinutes(1))
    );
    private final BucketProvider provider = new BucketProvider(props);

    @Test
    void sameIpAndEndpointReturnsSameBucket() {
        Bucket a = provider.bucketFor("create", "1.2.3.4");
        Bucket b = provider.bucketFor("create", "1.2.3.4");
        assertThat(a).isSameAs(b);
    }

    @Test
    void differentIpReturnsDifferentBucket() {
        Bucket a = provider.bucketFor("create", "1.2.3.4");
        Bucket b = provider.bucketFor("create", "1.2.3.5");
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void differentEndpointReturnsDifferentBucket() {
        Bucket a = provider.bucketFor("create", "1.2.3.4");
        Bucket b = provider.bucketFor("redirect", "1.2.3.4");
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void createBucketHasCapacityTen() {
        Bucket b = provider.bucketFor("create", "1.2.3.4");
        for (int i = 0; i < 10; i++) {
            assertThat(b.tryConsume(1)).isTrue();
        }
        assertThat(b.tryConsume(1)).isFalse();
    }

    @Test
    void redirectBucketHasCapacitySixty() {
        Bucket b = provider.bucketFor("redirect", "1.2.3.4");
        for (int i = 0; i < 60; i++) {
            assertThat(b.tryConsume(1)).isTrue();
        }
        assertThat(b.tryConsume(1)).isFalse();
    }
}
