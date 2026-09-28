package ai.aegis.gateway.insights;

import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Analytics suite: health score, AI benchmark, predictive patterns, and incident
 * clustering. All org-scoped and computed in the gateway (no LLM, no data egress).
 */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final InsightsService service;

    public InsightsController(InsightsService service) {
        this.service = service;
    }

    @GetMapping("/health-score")
    public Map<String, Object> health(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.health(principal);
    }

    @GetMapping("/benchmark")
    public Map<String, Object> benchmark(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.benchmark(principal);
    }

    @GetMapping("/predictions")
    public Map<String, Object> predictions(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.predictions(principal);
    }

    @GetMapping("/clusters")
    public Map<String, Object> clusters(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.clusters(principal);
    }
}
