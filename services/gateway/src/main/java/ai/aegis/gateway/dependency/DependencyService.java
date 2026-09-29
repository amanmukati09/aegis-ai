package ai.aegis.gateway.dependency;

import ai.aegis.gateway.incident.Incident;
import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.workspace.WorkspaceMemberRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dependency graph built from incident data (org-scoped). Nodes are affected components
 * inferred from incident titles/descriptions; edges are co-occurrence within the same
 * incident. On top of the graph and blast-radius BFS, this also computes a per-component
 * health score and flags "hub" components (high-degree nodes that are structurally
 * critical — an outage there ripples furthest) — the graph is not just descriptive, it
 * tells you where to focus resilience work.
 *
 * A Neo4j-backed adapter can replace this later without changing the API (see
 * ai.aegis.gateway.trackc.GraphStore for the dormant port).
 */
@Service
public class DependencyService {

    private static final List<String> COMPONENTS = List.of(
            "database", "api-gateway", "payments", "auth", "cache", "queue",
            "network", "storage", "frontend", "load-balancer", "dns"
    );

    private static final Map<String, Integer> SEVERITY_WEIGHT = Map.of(
            "critical", 4, "high", 3, "medium", 2, "low", 1
    );

    // Placeholder for a member with zero workspace memberships — Postgres/Hibernate
    // reject an empty "IN ()" list.
    private static final UUID NO_WORKSPACES_SENTINEL = new UUID(0L, 0L);

    private final IncidentRepository incidents;
    private final WorkspaceMemberRepository workspaceMembers;

    public DependencyService(IncidentRepository incidents, WorkspaceMemberRepository workspaceMembers) {
        this.incidents = incidents;
        this.workspaceMembers = workspaceMembers;
    }

    /** Per-incident: which components it mentions + its severity weight. */
    private record Hit(List<String> components, int severityWeight, boolean recent) {
    }

    /** Workspace-visibility-aware: a regular member's dependency graph is built only
     * from incidents they can see, so a workspace-scoped incident can't influence a
     * health score/blast-radius signal a non-member could reverse-engineer clues from. */
    private List<Hit> extractHits(AuthPrincipal principal) {
        Pageable p = PageRequest.of(0, 300);
        List<Incident> recent;
        if (principal.isSuperAdmin()) {
            recent = incidents.findAll(p).getContent();
        } else if (principal.isOrgAdmin()) {
            recent = incidents.findByOrgId(principal.orgId(), p).getContent();
        } else {
            List<UUID> memberWorkspaceIds = workspaceMembers.findWorkspaceIdsByUserId(principal.userId());
            if (memberWorkspaceIds.isEmpty()) {
                memberWorkspaceIds = List.of(NO_WORKSPACES_SENTINEL);
            }
            recent = incidents.findVisibleByOrgId(principal.orgId(), memberWorkspaceIds, p).getContent();
        }
        OffsetDateTime cutoff7d = OffsetDateTime.now().minusDays(7);

        List<Hit> hits = new ArrayList<>();
        for (Incident i : recent) {
            String text = ((i.getTitle() == null ? "" : i.getTitle()) + " "
                    + (i.getAnomalyDescription() == null ? "" : i.getAnomalyDescription())).toLowerCase();
            List<String> comps = COMPONENTS.stream().filter(text::contains).toList();
            if (comps.isEmpty()) {
                continue;
            }
            int weight = SEVERITY_WEIGHT.getOrDefault(
                    i.getSeverity() == null ? "" : i.getSeverity().toLowerCase(), 1);
            boolean isRecent = i.getDetectedAt() != null && i.getDetectedAt().isAfter(cutoff7d);
            hits.add(new Hit(comps, weight, isRecent));
        }
        return hits;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> graph(AuthPrincipal principal) {
        List<Hit> hits = extractHits(principal);

        Map<String, Integer> nodeCounts = new LinkedHashMap<>();
        Map<String, Integer> nodeSeverity = new LinkedHashMap<>();
        Map<String, Integer> nodeRecent = new LinkedHashMap<>();
        Map<String, Integer> edgeCounts = new LinkedHashMap<>();

        for (Hit h : hits) {
            for (String c : h.components()) {
                nodeCounts.merge(c, 1, Integer::sum);
                nodeSeverity.merge(c, h.severityWeight(), Integer::sum);
                if (h.recent()) {
                    nodeRecent.merge(c, 1, Integer::sum);
                }
            }
            for (int a = 0; a < h.components().size(); a++) {
                for (int b = a + 1; b < h.components().size(); b++) {
                    String key = h.components().get(a) + "|" + h.components().get(b);
                    edgeCounts.merge(key, 1, Integer::sum);
                }
            }
        }

        Map<String, Integer> healthScores = healthScores(nodeCounts, nodeSeverity, nodeRecent);
        Map<String, Integer> degree = degree(nodeCounts.keySet(), edgeCounts);

        List<Map<String, Object>> nodes = new ArrayList<>();
        nodeCounts.forEach((name, count) -> nodes.add(new LinkedHashMap<>(Map.of(
                "id", name, "label", name, "weight", count,
                "healthScore", healthScores.getOrDefault(name, 100),
                "degree", degree.getOrDefault(name, 0)))));

        List<Map<String, Object>> edges = new ArrayList<>();
        edgeCounts.forEach((key, count) -> {
            String[] parts = key.split("\\|");
            edges.add(Map.of("source", parts[0], "target", parts[1], "weight", count));
        });

        List<Map<String, Object>> criticalPaths = criticalPaths(nodeCounts.keySet(), degree, healthScores);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("hasData", !nodes.isEmpty());
        out.put("criticalPaths", criticalPaths);
        return out;
    }

    /**
     * Health score per component: starts at 100, deducted by severity-weighted incident
     * volume and a recency penalty (incidents in the last 7 days hurt more — a component
     * with old, resolved noise looks healthier than one actively flaring).
     */
    private Map<String, Integer> healthScores(Map<String, Integer> counts, Map<String, Integer> severityWeight,
                                              Map<String, Integer> recentCounts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String c : counts.keySet()) {
            int score = 100;
            score -= Math.min(60, severityWeight.getOrDefault(c, 0) * 3);
            score -= Math.min(25, recentCounts.getOrDefault(c, 0) * 5);
            out.put(c, Math.max(5, Math.min(100, score)));
        }
        return out;
    }

    private Map<String, Integer> degree(Set<String> nodeIds, Map<String, Integer> edgeCounts) {
        Map<String, Integer> degree = new LinkedHashMap<>();
        for (String id : nodeIds) {
            degree.put(id, 0);
        }
        for (String key : edgeCounts.keySet()) {
            String[] parts = key.split("\\|");
            degree.merge(parts[0], 1, Integer::sum);
            degree.merge(parts[1], 1, Integer::sum);
        }
        return degree;
    }

    /**
     * Hub/critical-path detection: the top-3 highest-degree components are the structural
     * hubs — a failure there has the widest blast radius. Risk is High at degree >= 4,
     * else Medium, matching the same bands as the original heuristic.
     */
    private List<Map<String, Object>> criticalPaths(Set<String> nodeIds, Map<String, Integer> degree,
                                                     Map<String, Integer> healthScores) {
        return nodeIds.stream()
                .sorted(Comparator.comparingInt((String id) -> degree.getOrDefault(id, 0)).reversed())
                .limit(3)
                .filter(id -> degree.getOrDefault(id, 0) > 0)
                .map(id -> {
                    int d = degree.getOrDefault(id, 0);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("component", id);
                    m.put("connections", d);
                    m.put("healthScore", healthScores.getOrDefault(id, 100));
                    m.put("risk", d >= 4 ? "high" : "medium");
                    return m;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> blastRadius(AuthPrincipal principal, String component) {
        List<Hit> hitList = extractHits(principal);
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        Map<String, Integer> incidentImpact = new LinkedHashMap<>();

        for (Hit h : hitList) {
            for (String c : h.components()) {
                incidentImpact.merge(c, 1, Integer::sum);
            }
            for (String a : h.components()) {
                for (String b : h.components()) {
                    if (!a.equals(b)) {
                        adj.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
                    }
                }
            }
        }

        String start = component.toLowerCase();
        Set<String> direct = adj.getOrDefault(start, Set.of());
        Set<String> indirect = new LinkedHashSet<>();
        for (String d : direct) {
            indirect.addAll(adj.getOrDefault(d, Set.of()));
        }
        indirect.remove(start);
        indirect.removeAll(direct);

        int totalImpact = incidentImpact.getOrDefault(start, 0);
        for (String d : direct) {
            totalImpact += incidentImpact.getOrDefault(d, 0);
        }
        for (String d : indirect) {
            totalImpact += incidentImpact.getOrDefault(d, 0);
        }
        int affectedCount = direct.size() + indirect.size();
        String severity = affectedCount > 3 ? "critical" : affectedCount > 1 ? "high" : "medium";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", start);
        out.put("directImpact", new ArrayList<>(direct));
        out.put("indirectImpact", new ArrayList<>(indirect));
        out.put("radius", affectedCount);
        out.put("totalIncidentImpact", totalImpact);
        out.put("severity", severity);
        return out;
    }
}
