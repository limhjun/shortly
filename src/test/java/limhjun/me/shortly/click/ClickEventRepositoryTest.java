package limhjun.me.shortly.click;

import limhjun.me.shortly.url.ShortUrl;
import limhjun.me.shortly.url.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL;TRACE_LEVEL_SYSTEM_OUT=0",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
@Sql(scripts = "classpath:sql/test-setup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ClickEventRepositoryTest {

    @Autowired ShortUrlRepository urls;
    @Autowired ClickEventRepository clicks;

    @Test
    void countsAndDistinctIpHashes() {
        ShortUrl url = urls.save(new ShortUrl(null, null, "https://example.com", Instant.now(), null));

        Instant now = Instant.now();
        clicks.save(new ClickEvent(null, url.getId(), now, "ipA", "ref", "Chrome"));
        clicks.save(new ClickEvent(null, url.getId(), now, "ipA", "ref", "Chrome"));
        clicks.save(new ClickEvent(null, url.getId(), now, "ipB", "ref", "Firefox"));

        assertThat(clicks.countByShortUrlId(url.getId())).isEqualTo(3);
        assertThat(clicks.countDistinctIpHashByShortUrlId(url.getId())).isEqualTo(2);
    }

    @Test
    void byDayReturnsRowsOrderedByDay() {
        ShortUrl url = urls.save(new ShortUrl(null, null, "https://example.com", Instant.now(), null));

        Instant base = Instant.parse("2026-05-01T10:00:00Z");
        clicks.save(new ClickEvent(null, url.getId(), base, "ip1", null, null));
        clicks.save(new ClickEvent(null, url.getId(), base.plus(1, ChronoUnit.DAYS), "ip2", null, null));
        clicks.save(new ClickEvent(null, url.getId(), base.plus(1, ChronoUnit.DAYS), "ip3", null, null));

        var rows = clicks.byDay(url.getId(), base.minus(1, ChronoUnit.DAYS));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getN()).isEqualTo(1);
        assertThat(rows.get(1).getN()).isEqualTo(2);
    }
}
