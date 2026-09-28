package ai.aegis.gateway.trackc;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Track C capability status: reports which optional streaming/graph/metrics adapters are
 * active. Everything is dormant on the core deployment; this endpoint lets the UI and ops
 * see what would light up once the heavy infrastructure is enabled (see LAPTOP_SETUP.md).
 */
@RestController
@RequestMapping("/api/trackc")
public class TrackCController {

    private final IngestionSource ingestion;
    private final MetricsStore metrics;
    private final GraphStore graph;

    public TrackCController(IngestionSource ingestion, MetricsStore metrics, GraphStore graph) {
        this.ingestion = ingestion;
        this.metrics = metrics;
        this.graph = graph;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<Map<String, Object>> capabilities = List.of(
                capability("ingestion", "Kafka streaming ingestion", ingestion.name(), ingestion.isActive(), ingestion.status()),
                capability("metrics", "TimescaleDB metrics + Grafana", metrics.name(), metrics.isActive(), metrics.status()),
                capability("graph", "Neo4j causal/dependency graph", graph.name(), graph.isActive(), graph.status())
        );
        boolean anyActive = capabilities.stream().anyMatch(c -> (boolean) c.get("active"));
        return Map.of(
                "mode", anyActive ? "extended" : "core",
                "capabilities", capabilities
        );
    }

    private Map<String, Object> capability(String key, String label, String adapter, boolean active, String status) {
        return Map.of(
                "key", key,
                "label", label,
                "adapter", adapter,
                "active", active,
                "status", status
        );
    }
}
