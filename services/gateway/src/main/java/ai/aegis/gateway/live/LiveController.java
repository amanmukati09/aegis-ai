package ai.aegis.gateway.live;

import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Live monitor snapshot. Provides current org metrics for the live dashboard; the
 * frontend polls this on an interval (react-query) for near-real-time updates. A
 * WebSocket/SSE push channel is a drop-in upgrade later, but polling is robust through
 * the Next proxy and adequate at this scale.
 */
@RestController
@RequestMapping("/api/live")
public class LiveController {

    private final IncidentRepository incidents;

    public LiveController(IncidentRepository incidents) {
        this.incidents = incidents;
    }

    @GetMapping("/state")
    public Map<String, Object> state(@AuthenticationPrincipal AuthPrincipal principal) {
        var orgId = principal.orgId();
        long total = incidents.countByOrgId(orgId);
        long open = incidents.countByOrgIdAndStatus(orgId, "open");
        var recent = incidents.findTop10ByOrgIdOrderByDetectedAtDesc(orgId).stream()
                .map(i -> Map.of(
                        "id", i.getId().toString(),
                        "title", i.getTitle() == null ? "" : i.getTitle(),
                        "severity", i.getSeverity() == null ? "unknown" : i.getSeverity(),
                        "status", i.getStatus(),
                        "detectedAt", i.getDetectedAt() == null ? "" : i.getDetectedAt().toString()))
                .toList();
        return Map.of(
                "timestamp", java.time.OffsetDateTime.now().toString(),
                "total", total,
                "open", open,
                "recent", recent
        );
    }
}
