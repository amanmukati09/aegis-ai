package ai.aegis.gateway.insights;

import ai.aegis.gateway.incident.Incident;
import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.workspace.WorkspaceMemberRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Analytics suite computed entirely in the gateway (the DB owner), org-scoped:
 *  - health score   : live posture from open/critical/velocity
 *  - benchmark       : AI effectiveness metrics (diagnosis/remediation/resolution rates, MTTR)
 *  - predictions     : pattern mining over history (peak hour/day, risky component, recurrence)
 *  - clustering      : greedy similarity grouping by extracted incident features
 *
 * No LLM and no data leaves the gateway; these are deterministic aggregations and
 * lightweight feature math, so they're cheap and safe on the small EC2 box.
 */
@Service
public class InsightsService {

    private static final int MAX_ROWS = 500;

    // component keyword -> label, first match wins (mirrors the RL triage buckets).
    private static final String[][] COMPONENTS = {
            {"database", "Database"}, {"postgres", "Database"}, {"sql", "Database"},
            {"redis", "Redis"}, {"cache", "Redis"},
            {"nginx", "Nginx"}, {"proxy", "Nginx"},
            {"gateway", "API Gateway"}, {"api", "API Gateway"},
            {"auth", "Auth"}, {"login", "Auth"},
            {"payment", "Payment"}, {"billing", "Payment"},
            {"network", "Network"}, {"dns", "Network"}, {"connection", "Network"},
            {"memory", "Memory"}, {"oom", "Memory"},
            {"cpu", "CPU"}, {"disk", "Disk"},
    };

    // Placeholder for a member with zero workspace memberships — Postgres/Hibernate
    // reject an empty "IN ()" list.
    private static final UUID NO_WORKSPACES_SENTINEL = new UUID(0L, 0L);

    private final IncidentRepository incidents;
    private final JdbcTemplate jdbc;
    private final WorkspaceMemberRepository workspaceMembers;

    public InsightsService(IncidentRepository incidents, JdbcTemplate jdbc, WorkspaceMemberRepository workspaceMembers) {
        this.incidents = incidents;
        this.jdbc = jdbc;
        this.workspaceMembers = workspaceMembers;
    }

    // ---- health score ---------------------------------------------------
    @Transactional(readOnly = true)
    public Map<String, Object> health(AuthPrincipal principal) {
        UUID orgId = principal.orgId();
        if (orgId == null) {
            return Map.of("score", 100, "status", "Excellent", "totalIncidents", 0);
        }
        long total = incidents.countByOrgId(orgId);
        long open = incidents.countByOrgIdAndStatus(orgId, "open");
        long resolved = incidents.countByOrgIdAndStatus(orgId, "resolved");

        Long critical1h = jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE org_id = ? AND detected_at >= now() - interval '1 hour'
                  AND lower(coalesce(severity,'')) = 'critical'
                """, Long.class, orgId);
        long crit = critical1h == null ? 0 : critical1h;

        int score = 100 - (int) (crit * 8) - (int) Math.max(0, open * 2);
        score = Math.max(10, Math.min(100, score));

        String status = score >= 85 ? "Excellent" : score >= 70 ? "Healthy"
                : score >= 50 ? "Degraded" : "Critical";

        String topRisk = topRiskComponent(principal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("status", status);
        out.put("totalIncidents", total);
        out.put("openIncidents", open);
        out.put("resolvedIncidents", resolved);
        out.put("critical1h", crit);
        out.put("incidentVelocity", crit);
        out.put("topRiskComponent", topRisk);
        out.put("resolutionRate", round(resolved / (double) Math.max(total, 1) * 100));
        return out;
    }

    private String topRiskComponent(AuthPrincipal principal) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(24);
        for (Incident i : fetch(principal)) {
            if (i.getDetectedAt() != null && i.getDetectedAt().isBefore(cutoff)) {
                continue;
            }
            String c = component(i);
            counts.merge(c, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("None");
    }

    // ---- benchmark ------------------------------------------------------
    @Transactional(readOnly = true)
    public Map<String, Object> benchmark(AuthPrincipal principal) {
        UUID orgId = principal.orgId();
        if (orgId == null) {
            return Map.of("totalIncidents", 0);
        }
        long total = incidents.countByOrgId(orgId);
        long resolved = incidents.countByOrgIdAndStatus(orgId, "resolved");
        Double mttr = incidents.avgResolutionHours(orgId);

        Long withRca = jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE org_id = ? AND root_cause IS NOT NULL AND root_cause <> ''
                """, Long.class, orgId);
        Long withRemediation = jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE org_id = ? AND remediation_action IS NOT NULL AND remediation_action <> ''
                """, Long.class, orgId);
        Long recent7d = jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE org_id = ? AND detected_at >= now() - interval '7 days'
                """, Long.class, orgId);

        long rca = withRca == null ? 0 : withRca;
        long rem = withRemediation == null ? 0 : withRemediation;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalIncidents", total);
        out.put("diagnosisAccuracy", round(rca / (double) Math.max(total, 1) * 100));
        out.put("remediationRate", round(rem / (double) Math.max(total, 1) * 100));
        out.put("resolutionRate", round(resolved / (double) Math.max(total, 1) * 100));
        out.put("avgResolutionHours", mttr == null ? 0.0 : round(mttr));
        out.put("recent7d", recent7d == null ? 0 : recent7d);
        out.put("withRootCause", rca);
        out.put("withRemediation", rem);
        return out;
    }

    // ---- predictions ----------------------------------------------------
    @Transactional(readOnly = true)
    public Map<String, Object> predictions(AuthPrincipal principal) {
        List<Incident> all = fetch(principal);
        List<Map<String, Object>> preds = new ArrayList<>();
        if (all.isEmpty()) {
            return Map.of("predictions", preds, "riskLevel", "LOW",
                    "totalIncidents", 0, "summary", "No historical data available");
        }

        // peak hour
        Map<Integer, Integer> byHour = new LinkedHashMap<>();
        Map<String, Integer> byComponent = new LinkedHashMap<>();
        int highSev = 0;
        for (Incident i : all) {
            if (i.getDetectedAt() != null) {
                byHour.merge(i.getDetectedAt().getHour(), 1, Integer::sum);
            }
            byComponent.merge(component(i), 1, Integer::sum);
            String s = (i.getSeverity() == null ? "" : i.getSeverity()).toLowerCase();
            if (s.equals("high") || s.equals("critical")) {
                highSev++;
            }
        }
        int n = all.size();

        byHour.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e ->
                preds.add(pred("time_pattern", "Peak incident hour: " + e.getKey() + ":00",
                        "Most incidents occur around " + e.getKey() + ":00. Increase monitoring at this time.",
                        Math.min(90, e.getValue() / (double) n * 100))));

        byComponent.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> preds.add(pred("component_risk", "High-risk component: " + e.getKey(),
                        "'" + e.getKey() + "' accounts for " + e.getValue() + " incidents. Monitor for recurrence.",
                        Math.min(85, e.getValue() / (double) n * 100))));

        preds.add(pred("severity_trend", highSev + " high/critical incidents",
                Math.round(highSev / (double) n * 100) + "% of incidents are HIGH or CRITICAL severity.",
                80));

        double avgConf = preds.stream().mapToDouble(p -> (double) p.get("confidence")).average().orElse(0);
        String risk = avgConf > 70 ? "HIGH" : avgConf > 40 ? "MEDIUM" : "LOW";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("predictions", preds);
        out.put("riskLevel", risk);
        out.put("totalIncidents", n);
        out.put("summary", "Based on " + n + " incidents, overall risk is " + risk
                + " with " + preds.size() + " active predictions.");
        return out;
    }

    // ---- clustering -----------------------------------------------------
    @Transactional(readOnly = true)
    public Map<String, Object> clusters(AuthPrincipal principal) {
        List<Incident> all = fetch(principal);
        if (all.size() < 2) {
            return Map.of("clusters", List.of(), "totalIncidents", all.size(),
                    "totalClusters", 0, "summary", "Not enough incidents to cluster");
        }

        List<Feature> features = all.stream().map(Feature::from).toList();
        boolean[] assigned = new boolean[features.size()];
        List<Map<String, Object>> clusters = new ArrayList<>();

        for (int i = 0; i < features.size(); i++) {
            if (assigned[i]) {
                continue;
            }
            Feature f1 = features.get(i);
            List<Feature> members = new ArrayList<>();
            members.add(f1);
            assigned[i] = true;
            for (int j = i + 1; j < features.size(); j++) {
                if (!assigned[j] && f1.similarity(features.get(j)) >= 0.6) {
                    members.add(features.get(j));
                    assigned[j] = true;
                }
            }
            clusters.add(clusterSummary(members));
        }

        clusters.sort(Comparator.comparingInt((Map<String, Object> c) -> (int) c.get("count")).reversed());
        long clustered = clusters.stream().mapToInt(c -> (int) c.get("count")).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusters", clusters);
        out.put("totalIncidents", all.size());
        out.put("totalClusters", clusters.size());
        out.put("summary", "Found " + clusters.size() + " clusters covering " + clustered + " incidents.");
        return out;
    }

    private Map<String, Object> clusterSummary(List<Feature> members) {
        Map<String, Integer> sevDist = new LinkedHashMap<>();
        Map<String, Integer> tagCounts = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (Feature f : members) {
            sevDist.merge(f.severity, 1, Integer::sum);
            for (String t : f.tags) {
                tagCounts.merge(t, 1, Integer::sum);
            }
            ids.add(f.id);
        }
        List<String> common = tagCounts.entrySet().stream()
                .filter(e -> e.getValue() >= members.size() * 0.5)
                .map(Map.Entry::getKey).toList();

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", clusterName(members.get(0), common));
        c.put("count", members.size());
        c.put("commonFeatures", common);
        c.put("severityDistribution", sevDist);
        c.put("incidentIds", ids.size() > 8 ? ids.subList(0, 8) : ids);
        return c;
    }

    private String clusterName(Feature f, List<String> common) {
        if (f.tags.contains("Memory")) return "Memory Exhaustion Cluster";
        if (f.tags.contains("Nginx") && f.tags.contains("Crash")) return "Nginx Crash Cluster";
        if (f.tags.contains("Network") && f.tags.contains("Database")) return "DB Connectivity Cluster";
        if (f.tags.contains("Crash")) return "Service Crash Cluster";
        if (f.tags.contains("Database")) return "Database Issue Cluster";
        if (f.tags.contains("Network")) return "Network Issue Cluster";
        if (!common.isEmpty()) return common.get(0) + " Cluster";
        return f.component + " Cluster";
    }

    // ---- helpers --------------------------------------------------------
    /** Workspace-visibility-aware: predictions/clusters/top-risk-component are computed
     * only from incidents the caller can see (aggregated output, but clusters() does
     * include raw incident IDs — those are harmless on their own since GET /incidents/{id}
     * enforces the same visibility rule, but there's no reason to hand them out either). */
    private List<Incident> fetch(AuthPrincipal principal) {
        Pageable p = PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "detectedAt"));
        if (principal.isSuperAdmin()) {
            return incidents.findAll(p).getContent();
        }
        if (principal.isOrgAdmin()) {
            return incidents.findByOrgId(principal.orgId(), p).getContent();
        }
        List<UUID> memberWorkspaceIds = workspaceMembers.findWorkspaceIdsByUserId(principal.userId());
        if (memberWorkspaceIds.isEmpty()) {
            memberWorkspaceIds = List.of(NO_WORKSPACES_SENTINEL);
        }
        return incidents.findVisibleByOrgId(principal.orgId(), memberWorkspaceIds, p).getContent();
    }

    private static String component(Incident i) {
        String text = ((i.getTitle() == null ? "" : i.getTitle()) + " "
                + (i.getAnomalyDescription() == null ? "" : i.getAnomalyDescription())).toLowerCase();
        for (String[] pair : COMPONENTS) {
            if (text.contains(pair[0])) {
                return pair[1];
            }
        }
        return "Other";
    }

    private static Map<String, Object> pred(String type, String title, String detail, double confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("detail", detail);
        m.put("confidence", round(confidence));
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Feature vector for greedy clustering — boolean tags + severity + component. */
    private static final class Feature {
        final String id;
        final String severity;
        final String component;
        final java.util.Set<String> tags;

        private Feature(String id, String severity, String component, java.util.Set<String> tags) {
            this.id = id;
            this.severity = severity;
            this.component = component;
            this.tags = tags;
        }

        static Feature from(Incident i) {
            String text = ((i.getTitle() == null ? "" : i.getTitle()) + " "
                    + (i.getAnomalyDescription() == null ? "" : i.getAnomalyDescription())
                    + " " + (i.getRootCause() == null ? "" : i.getRootCause())).toLowerCase();
            java.util.Set<String> tags = new java.util.LinkedHashSet<>();
            if (text.contains("cpu")) tags.add("CPU");
            if (text.contains("memory") || text.contains("oom") || text.contains("killed")) tags.add("Memory");
            if (text.contains("disk")) tags.add("Disk");
            if (text.contains("network") || text.contains("timeout") || text.contains("connection")) tags.add("Network");
            if (text.contains("nginx")) tags.add("Nginx");
            if (text.contains("database") || text.contains("sql") || text.contains("db")) tags.add("Database");
            if (text.contains("crash")) tags.add("Crash");
            return new Feature(i.getId().toString(),
                    i.getSeverity() == null ? "unknown" : i.getSeverity(),
                    component(i), tags);
        }

        double similarity(Feature o) {
            int score = 0;
            int total = 0;
            for (String tag : java.util.Set.of("CPU", "Memory", "Disk", "Network", "Nginx", "Database", "Crash")) {
                total++;
                if (tags.contains(tag) == o.tags.contains(tag)) {
                    score++;
                }
            }
            total++;
            if (severity.equalsIgnoreCase(o.severity)) score++;
            total++;
            if (component.equals(o.component)) score++;
            return score / (double) total;
        }
    }
}
