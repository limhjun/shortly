package limhjun.me.shortly.analytics;

import limhjun.me.shortly.analytics.dto.AnalyticsResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/urls")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/{code}/stats")
    public AnalyticsResponse stats(@PathVariable String code) {
        return service.stats(code);
    }
}
