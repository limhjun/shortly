package limhjun.me.shortly.url;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import limhjun.me.shortly.click.ClickRecordedEvent;
import limhjun.me.shortly.url.dto.CreateUrlRequest;
import limhjun.me.shortly.url.dto.UrlResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@RestController
public class UrlController {

    private final UrlService urlService;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String baseUrl;

    public UrlController(UrlService urlService,
                         ApplicationEventPublisher events,
                         Clock clock,
                         @Value("${app.base-url}") String baseUrl) {
        this.urlService = urlService;
        this.events = events;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    @PostMapping("/api/urls")
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest req) {
        ShortUrl saved = urlService.create(req.url());
        UrlResponse body = new UrlResponse(
                saved.getCode(),
                baseUrl + "/" + saved.getCode(),
                saved.getOriginalUrl(),
                saved.getCreatedAt());
        return ResponseEntity
                .created(URI.create(body.shortUrl()))
                .body(body);
    }

    @GetMapping("/{code:[0-9a-zA-Z]{1,11}}")
    public ResponseEntity<Void> redirect(@PathVariable String code,
                                          HttpServletRequest request) {
        Optional<ShortUrl> opt = urlService.resolve(code);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ShortUrl url = opt.get();
        if (url.getExpiresAt() != null && url.getExpiresAt().isBefore(Instant.now(clock))) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        events.publishEvent(new ClickRecordedEvent(
                url.getId(),
                Instant.now(clock),
                clientIp(request),
                request.getHeader("Referer"),
                request.getHeader("User-Agent")
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, url.getOriginalUrl());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
