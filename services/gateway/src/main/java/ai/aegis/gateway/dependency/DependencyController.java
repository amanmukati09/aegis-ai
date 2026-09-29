package ai.aegis.gateway.dependency;

import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dependency graph built from incident data (org-scoped): topology, per-component health
 * score, hub/critical-path detection, and blast-radius impact. See DependencyService for
 * the heuristic. (A Neo4j-backed adapter can replace this later without changing the API.)
 */
@RestController
@RequestMapping("/api/dependency")
public class DependencyController {

    private final DependencyService service;

    public DependencyController(DependencyService service) {
        this.service = service;
    }

    @GetMapping("/graph")
    public Map<String, Object> graph(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.graph(principal);
    }

    @GetMapping("/blast-radius/{component}")
    public Map<String, Object> blastRadius(@AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable String component) {
        return service.blastRadius(principal, component);
    }
}
