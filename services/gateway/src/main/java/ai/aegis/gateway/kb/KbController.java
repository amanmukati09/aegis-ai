package ai.aegis.gateway.kb;

import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knowledge base: articles distilled from resolved incidents. Org-scoped like everything
 * else — a KB article never crosses an organization boundary.
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KbService service;

    public KbController(KbService service) {
        this.service = service;
    }

    @GetMapping("/articles")
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.list(principal);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@AuthenticationPrincipal AuthPrincipal principal,
                                            @RequestParam("q") String q) {
        return service.search(principal, q);
    }

    @PostMapping("/generate/{incidentId}")
    public Map<String, Object> generate(@AuthenticationPrincipal AuthPrincipal principal,
                                        @PathVariable UUID incidentId, HttpServletRequest http) {
        return service.generateFromIncident(principal, incidentId, clientIp(http));
    }

    @PostMapping("/generate/backfill")
    public Map<String, Object> backfill(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest http) {
        return service.generateFromAllResolved(principal, clientIp(http));
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
