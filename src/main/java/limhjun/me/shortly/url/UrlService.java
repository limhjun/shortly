package limhjun.me.shortly.url;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Service
public class UrlService {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final int MAX_URL_LENGTH = 2048;

    private final ShortUrlRepository repository;
    private final Clock clock;

    public UrlService(ShortUrlRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ShortUrl create(String originalUrl) {
        validate(originalUrl);
        ShortUrl entity = new ShortUrl(
                null, null, originalUrl, Instant.now(clock), null);
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public Optional<ShortUrl> resolve(String code) {
        return resolveInternal(code);
    }

    @Cacheable(value = "shortUrl", key = "#code", unless = "#result == null || #result.equals(T(java.util.Optional).empty())")
    private Optional<ShortUrl> resolveInternal(String code) {
        return repository.findByCode(code);
    }

    private void validate(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new InvalidUrlException("originalUrl must not be blank");
        }
        if (originalUrl.length() > MAX_URL_LENGTH) {
            throw new InvalidUrlException("originalUrl exceeds " + MAX_URL_LENGTH + " chars");
        }
        try {
            URI uri = new URI(originalUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                throw new InvalidUrlException("scheme must be http or https");
            }
            if (uri.getHost() == null) {
                throw new InvalidUrlException("URL must have a host");
            }
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("malformed URL: " + e.getMessage());
        }
    }
}
