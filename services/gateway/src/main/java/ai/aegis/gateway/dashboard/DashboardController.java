package ai.aegis.gateway.dashboard;

import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.incident.dto.IncidentDtos.IncidentView;
import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.workspace.WorkspaceMemberRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Org-scoped dashboard aggregates: totals, status/severity breakdowns, MTTR, and
 * recent incidents. Computed from the incidents table for the caller's organization.
 * Aggregate counts remain org-wide (no per-incident detail), but the "recent" list
 * respects workspace visibility since it exposes titles/severities.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    // A workspace-membership placeholder that never matches a real row — Postgres/
    // Hibernate reject an empty "IN ()" list, so a member with zero workspaces still
    // needs a non-empty (but harmless) list to pass into the visibility-aware query.
    private static final UUID NO_WORKSPACES_SENTINEL = new UUID(0L, 0L);

    private final IncidentRepository incidents;
    private final WorkspaceMemberRepository workspaceMembers;

    public DashboardController(IncidentRepository incidents, WorkspaceMemberRepository workspaceMembers) {
        this.incidents = incidents;
        this.workspaceMembers = workspaceMembers;
    }

    public record Summary(
            long total,
            long open,
            long resolved,
            Double mttrHours,
            Map<String, Long> byStatus,
            Map<String, Long> bySeverity,
            List<IncidentView> recent
    ) {
    }

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal AuthPrincipal principal) {
        UUID orgId = principal.orgId();
        // super_admin without an org sees zeros here; org-wide analytics arrive later.
        if (orgId == null) {
            return new Summary(0, 0, 0, null, Map.of(), Map.of(), List.of());
        }

        long total = incidents.countByOrgId(orgId);
        long open = incidents.countByOrgIdAndStatus(orgId, "open");
        long resolved = incidents.countByOrgIdAndStatus(orgId, "resolved");
        Double mttr = incidents.avgResolutionHours(orgId);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        incidents.countGroupByStatus(orgId).forEach(r -> byStatus.put(r.getLabel(), r.getTotal()));

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        incidents.countGroupBySeverity(orgId).forEach(r -> bySeverity.put(r.getLabel(), r.getTotal()));

        List<IncidentView> recent = (principal.bypassesWorkspaceVisibility()
                ? incidents.findTop10ByOrgIdOrderByDetectedAtDesc(orgId)
                : incidents.findTop10VisibleByOrgId(orgId, visibleWorkspaceIds(principal), PageRequest.of(0, 10)))
                .stream().map(IncidentView::of).toList();

        return new Summary(total, open, resolved, mttr, byStatus, bySeverity, recent);
    }

    private List<UUID> visibleWorkspaceIds(AuthPrincipal principal) {
        List<UUID> ids = workspaceMembers.findWorkspaceIdsByUserId(principal.userId());
        return ids.isEmpty() ? List.of(NO_WORKSPACES_SENTINEL) : ids;
    }
}
