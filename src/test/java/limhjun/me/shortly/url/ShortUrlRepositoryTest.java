package limhjun.me.shortly.url;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL;TRACE_LEVEL_SYSTEM_OUT=0",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Sql(scripts = "classpath:sql/test-setup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ShortUrlRepositoryTest {

    @Autowired ShortUrlRepository repository;

    @Test
    void persistAssignsIdAndCode() {
        ShortUrl saved = repository.save(new ShortUrl(
                null, null, "https://example.com", Instant.now(), null));

        assertThat(saved.getId()).isGreaterThanOrEqualTo(100_000L);
        assertThat(saved.getCode()).isEqualTo(Base62Encoder.encode(saved.getId()));
    }

    @Test
    void findByCodeReturnsEmptyForUnknown() {
        Optional<ShortUrl> result = repository.findByCode("nope");
        assertThat(result).isEmpty();
    }

    @Test
    void findByCodeReturnsEntityForKnown() {
        ShortUrl saved = repository.save(new ShortUrl(
                null, null, "https://example.com", Instant.now(), null));

        Optional<ShortUrl> result = repository.findByCode(saved.getCode());
        assertThat(result).isPresent();
        assertThat(result.get().getOriginalUrl()).isEqualTo("https://example.com");
    }
}
