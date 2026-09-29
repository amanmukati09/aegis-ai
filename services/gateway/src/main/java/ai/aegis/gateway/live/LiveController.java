package ai.aegis.gateway.live;

import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.workspace.WorkspaceMemberRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live monitor snapshot. Provides current org metrics for the live dashboard; the
 * frontend polls this on an interval (react-query) for near-real-time updates. A
 * WebSocket/SSE push channel is a drop-in upgrade later, but polling is robust through
 * the Next proxy and adequate at this scale. The "recent" feed respects workspace
 * visibility (titles/severities are per-incident detail); the total/open counts stay
 * org-wide aggregates.
 */
@RestController
@RequestMapping("/api/live")
public class LiveController {

    // Placeholder for a member with zero workspace memberships — Postgres/Hibernate
    // reject an empty "IN ()" list, so this stands in for "no real workspace matches".
    private static final UUID NO_WORKSPACES_SENTINEL = new UUID(0L, 0L);

    private final IncidentRepository incidents;
    private final WorkspaceMemberRepository workspaceMembers;

    public LiveController(IncidentRepository incidents, WorkspaceMemberRepository workspaceMembers) {
        this.incidents = incidents;
        this.workspaceMembers = workspaceMembers;
    }

    @GetMapping("/state")
    public Map<String, Object> state(@AuthenticationPrincipal AuthPrincipal principal) {
        var orgId = principal.orgId();
        long total = incidents.countByOrgId(orgId);
        long open = incidents.countByOrgIdAndStatus(orgId, "open");
        var recentIncidents = principal.bypassesWorkspaceVisibility()
                ? incidents.findTop10ByOrgIdOrderByDetectedAtDesc(orgId)
                : incidents.findTop10VisibleByOrgId(orgId, visibleWorkspaceIds(principal), PageRequest.of(0, 10));
        var recent = recentIncidents.stream()
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

    private List<UUID> visibleWorkspaceIds(AuthPrincipal principal) {
        List<UUID> ids = workspaceMembers.findWorkspaceIdsByUserId(principal.userId());
        return ids.isEmpty() ? List.of(NO_WORKSPACES_SENTINEL) : ids;
    }
}
