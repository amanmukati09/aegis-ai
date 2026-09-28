package ai.aegis.gateway.triage;

import ai.aegis.gateway.incident.Incident;
import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.ml.MlClient;
import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RL triage: build a feature view of the org's incidents, hand them to the ML Q-learning
 * agent for prioritization, and expose training on resolution outcomes. Component is derived
 * heuristically from incident text because the core schema has no explicit component column.
 */
@Service
public class TriageService {

    private static final int MAX_INCIDENTS = 200;

    // Ordered keyword -> component; first hit wins. Mirrors the ML agent's component buckets.
    private static final String[][] COMPONENT_KEYWORDS = {
            {"database", "database"}, {"postgres", "database"}, {"sql", "database"},
            {"redis", "redis"}, {"cache", "redis"},
            {"nginx", "nginx"}, {"proxy", "nginx"},
            {"gateway", "gateway"}, {"api", "gateway"},
            {"auth", "auth"}, {"login", "auth"}, {"token", "auth"},
            {"payment", "payment"}, {"billing", "payment"},
            {"network", "network"}, {"dns", "network"}, {"connection", "network"},
    };

    private final IncidentRepository incidents;
    private final MlClient ml;

    public TriageService(IncidentRepository incidents, MlClient ml) {
        this.incidents = incidents;
        this.ml = ml;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue(AuthPrincipal principal) {
        List<Incident> source = fetch(principal);
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, String> titles = new java.util.HashMap<>();
        for (Incident i : source) {
            items.add(feature(i));
            titles.put(i.getId().toString(), i.getTitle());
        }
        Map<String, Object> resp = ml.triageQueue(Map.of("incidents", items));
        Object queue = resp == null ? null : resp.get("queue");
        if (!(queue instanceof List<?> list)) {
            return List.of();
        }
        // Attach human-friendly title back onto the ranked rows (the ML agent only
        // returns ids/features), mapped by incident_id.
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : list) {
            if (row instanceof Map<?, ?> m) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                copy.put("title", titles.getOrDefault(String.valueOf(copy.get("incident_id")), ""));
                out.add(copy);
            }
        }
        return out;
    }

    /** Train the agent on resolved incidents (reward = speed of resolution vs severity). */
    @Transactional(readOnly = true)
    public Map<String, Object> train(AuthPrincipal principal) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Incident i : fetch(principal)) {
            if (i.getResolvedAt() != null && i.getDetectedAt() != null) {
                Map<String, Object> f = feature(i);
                double hours = Duration.between(i.getDetectedAt(), i.getResolvedAt()).toMinutes() / 60.0;
                f.put("resolution_hours", Math.max(hours, 0.01));
                items.add(f);
            }
        }
        Map<String, Object> resp = ml.triageTrain(Map.of("incidents", items));
        return resp == null ? Map.of("trained_on", 0) : resp;
    }

    private List<Incident> fetch(AuthPrincipal principal) {
        Pageable p = PageRequest.of(0, MAX_INCIDENTS, Sort.by(Sort.Direction.DESC, "detectedAt"));
        return principal.isSuperAdmin()
                ? incidents.findAll(p).getContent()
                : incidents.findByOrgId(principal.orgId(), p).getContent();
    }

    private Map<String, Object> feature(Incident i) {
        Map<String, Object> f = new java.util.HashMap<>();
        f.put("id", i.getId().toString());
        f.put("severity", i.getSeverity() == null ? "medium" : i.getSeverity());
        f.put("status", i.getStatus());
        f.put("component", deriveComponent(i));
        return f;
    }

    private String deriveComponent(Incident i) {
        String text = ((i.getTitle() == null ? "" : i.getTitle()) + " "
                + (i.getAnomalyDescription() == null ? "" : i.getAnomalyDescription())).toLowerCase();
        for (String[] pair : COMPONENT_KEYWORDS) {
            if (text.contains(pair[0])) {
                return pair[1];
            }
        }
        return "other";
    }
}
