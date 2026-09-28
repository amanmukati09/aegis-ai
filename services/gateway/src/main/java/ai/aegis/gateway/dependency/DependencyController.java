package ai.aegis.gateway.dependency;

import ai.aegis.gateway.incident.Incident;
import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency graph built from incident data (org-scoped). Nodes are affected
 * components inferred from incident titles/descriptions; node weight = incident count.
 * A simple heuristic that produces a real, queryable topology for the graph view.
 * (A Neo4j-backed adapter can replace this later without changing the API.)
 */
@RestController
@RequestMapping("/api/dependency")
public class DependencyController {

    private final IncidentRepository incidents;

    private static final List<String> COMPONENTS = List.of(
            "database", "api-gateway", "payments", "auth", "cache", "queue",
            "network", "storage", "frontend", "load-balancer", "dns"
    );

    public DependencyController(IncidentRepository incidents) {
        this.incidents = incidents;
    }

    @GetMapping("/graph")
    public Map<String, Object> graph(@AuthenticationPrincipal AuthPrincipal principal) {
        List<Incident> recent = incidents
                .findByOrgId(principal.orgId(), PageRequest.of(0, 300))
                .getContent();

        Map<String, Integer> nodeCounts = new LinkedHashMap<>();
        Map<String, Integer> edgeCounts = new LinkedHashMap<>();

        for (Incident i : recent) {
            String text = ((i.getTitle() == null ? "" : i.getTitle()) + " "
                    + (i.getAnomalyDescription() == null ? "" : i.getAnomalyDescription())).toLowerCase();
            List<String> hits = new ArrayList<>();
            for (String c : COMPONENTS) {
                if (text.contains(c)) {
                    hits.add(c);
                    nodeCounts.merge(c, 1, Integer::sum);
                }
            }
            // Co-occurrence edges between components mentioned in the same incident.
            for (int a = 0; a < hits.size(); a++) {
                for (int b = a + 1; b < hits.size(); b++) {
                    String key = hits.get(a) + "|" + hits.get(b);
                    edgeCounts.merge(key, 1, Integer::sum);
                }
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        nodeCounts.forEach((name, count) -> nodes.add(Map.of("id", name, "label", name, "weight", count)));

        List<Map<String, Object>> edges = new ArrayList<>();
        edgeCounts.forEach((key, count) -> {
            String[] parts = key.split("\\|");
            edges.add(Map.of("source", parts[0], "target", parts[1], "weight", count));
        });

        return Map.of("nodes", nodes, "edges", edges, "hasData", !nodes.isEmpty());
    }

    @GetMapping("/blast-radius/{component}")
    public Map<String, Object> blastRadius(@AuthenticationPrincipal AuthPrincipal principal,
                                           @org.springframework.web.bind.annotation.PathVariable String component) {
        // Rebuild the co-occurrence adjacency, then BFS from the component to find the
        // set of components reachable within 2 hops (the likely blast radius).
        List<Incident> recent = incidents.findByOrgId(principal.orgId(), PageRequest.of(0, 300)).getContent();
        Map<String, java.util.Set<String>> adj = new LinkedHashMap<>();
        for (Incident i : recent) {
            String text = ((i.getTitle() == null ? "" : i.getTitle()) + " "
                    + (i.getAnomalyDescription() == null ? "" : i.getAnomalyDescription())).toLowerCase();
            List<String> hits = COMPONENTS.stream().filter(text::contains).toList();
            for (String a : hits) {
                for (String b : hits) {
                    if (!a.equals(b)) {
                        adj.computeIfAbsent(a, k -> new java.util.LinkedHashSet<>()).add(b);
                    }
                }
            }
        }
        String start = component.toLowerCase();
        java.util.Set<String> direct = adj.getOrDefault(start, java.util.Set.of());
        java.util.Set<String> indirect = new java.util.LinkedHashSet<>();
        for (String d : direct) {
            indirect.addAll(adj.getOrDefault(d, java.util.Set.of()));
        }
        indirect.remove(start);
        indirect.removeAll(direct);
        return Map.of(
                "component", start,
                "directImpact", new ArrayList<>(direct),
                "indirectImpact", new ArrayList<>(indirect),
                "radius", direct.size() + indirect.size()
        );
    }
}
