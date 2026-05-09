package limhjun.me.shortly.click;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import ua_parser.Client;
import ua_parser.Parser;

@Component
@Slf4j
public class ClickRecorder {

    private final ClickEventRepository repository;
    private final IpHasher ipHasher;
    private final Parser uaParser = new Parser();
    private final Counter recordedCounter;
    private final Counter errorCounter;

    public ClickRecorder(ClickEventRepository repository,
                         IpHasher ipHasher,
                         MeterRegistry registry) {
        this.repository = repository;
        this.ipHasher = ipHasher;
        this.recordedCounter = Counter.builder("shortly.click.recorded").register(registry);
        this.errorCounter = Counter.builder("shortly.click.recorder.errors").register(registry);
    }

    @Async("clickRecorderExecutor")
    @EventListener
    public void onClick(ClickRecordedEvent event) {
        try {
            String ipHash = ipHasher.hash(event.ip());
            String uaFamily = parseUaFamily(event.userAgent());
            ClickEvent ce = new ClickEvent(
                    null,
                    event.shortUrlId(),
                    event.clickedAt(),
                    ipHash,
                    truncate(event.referrer(), 2048),
                    uaFamily
            );
            repository.save(ce);
            recordedCounter.increment();
        } catch (Exception e) {
            errorCounter.increment();
            log.warn("click recorder failed: shortUrlId={} error={}",
                    event.shortUrlId(), e.toString());
        }
    }

    private String parseUaFamily(String ua) {
        if (ua == null || ua.isBlank()) return null;
        try {
            Client c = uaParser.parse(ua);
            String name = c.userAgent.family;
            String major = c.userAgent.major;
            return major != null ? (name + " " + major) : name;
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
