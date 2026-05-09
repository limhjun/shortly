package limhjun.me.shortly.analytics;

import limhjun.me.shortly.analytics.dto.AnalyticsResponse;
import limhjun.me.shortly.url.ShortUrlNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AnalyticsController.class)
class AnalyticsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AnalyticsService service;

    @Test
    void returnsStatsAs200() throws Exception {
        when(service.stats("ph7")).thenReturn(new AnalyticsResponse(
                "ph7", 42L, 7L, List.of(), List.of()));

        mvc.perform(get("/api/urls/ph7/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(42))
                .andExpect(jsonPath("$.uniqueVisitors").value(7));
    }

    @Test
    void returns404WhenServiceThrowsNotFound() throws Exception {
        when(service.stats("zzz")).thenThrow(new ShortUrlNotFoundException("zzz"));
        mvc.perform(get("/api/urls/zzz/stats"))
                .andExpect(status().isNotFound());
    }
}
