package limhjun.me.shortly.url;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UrlServiceTest {

    private final ShortUrlRepository repo = mock(ShortUrlRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);
    private final UrlService service = new UrlService(repo, clock);

    @Test
    void createPersistsAndReturns() {
        ShortUrl persisted = new ShortUrl(100_000L, "ph7", "https://example.com",
                Instant.parse("2026-05-09T10:00:00Z"), null);
        when(repo.save(any(ShortUrl.class))).thenReturn(persisted);

        ShortUrl out = service.create("https://example.com");

        assertThat(out.getCode()).isEqualTo("ph7");
        assertThat(out.getOriginalUrl()).isEqualTo("https://example.com");
    }

    @Test
    void createRejectsNonHttpScheme() {
        assertThatThrownBy(() -> service.create("javascript:alert(1)"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void createRejectsMalformedUrl() {
        assertThatThrownBy(() -> service.create("not a url"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void resolveByCodeDelegates() {
        ShortUrl found = new ShortUrl(100_000L, "ph7", "https://example.com",
                Instant.now(), null);
        when(repo.findByCode("ph7")).thenReturn(Optional.of(found));

        Optional<ShortUrl> out = service.resolve("ph7");

        assertThat(out).isPresent();
        assertThat(out.get().getCode()).isEqualTo("ph7");
    }
}
