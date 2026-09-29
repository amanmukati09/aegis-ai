package ai.aegis.gateway.kb;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.incident.Incident;
import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.incident.IncidentService;
import ai.aegis.gateway.ml.MlClient;
import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knowledge base: turns resolved incidents into reusable articles (symptoms / root cause /
 * solution / prevention) so the next engineer who hits the same class of problem finds the
 * answer instead of re-diagnosing from scratch. Generation is LLM-assisted with a safe
 * fallback; search is a simple org-scoped keyword match (no vector store needed at this
 * scale — swappable later using the same embedding pattern incidents already use).
 */
@Service
public class KbService {

    private final KbArticleRepository articles;
    private final IncidentRepository incidents;
    private final MlClient ml;
    private final AuditService auditService;
    private final IncidentService incidentService;

    public KbService(KbArticleRepository articles, IncidentRepository incidents,
                     MlClient ml, AuditService auditService, IncidentService incidentService) {
        this.articles = articles;
        this.incidents = incidents;
        this.ml = ml;
        this.auditService = auditService;
        this.incidentService = incidentService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(AuthPrincipal principal) {
        return articles.findByOrgIdOrderByCreatedAtDesc(principal.orgId()).stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(AuthPrincipal principal, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        return articles.search(principal.orgId(), q).stream()
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", row[0] == null ? "" : row[0].toString());
                    m.put("title", row[1] == null ? "" : row[1].toString());
                    m.put("category", row[2] == null ? "" : row[2].toString());
                    m.put("difficulty", row[3] == null ? "" : row[3].toString());
                    m.put("snippet", row[4] == null ? "" : row[4].toString());
                    return m;
                })
                .toList();
    }

    /** Generate one article from a resolved incident. Idempotent per incident. */
    @Transactional
    public Map<String, Object> generateFromIncident(AuthPrincipal principal, UUID incidentId, String ip) {
        Incident incident = principal.isSuperAdmin()
                ? incidents.findById(incidentId).orElse(null)
                : incidents.findByIdAndOrgId(incidentId, principal.orgId()).orElse(null);
        // Same visibility rule as viewing the incident directly: generating a KB article
        // republishes its content org-wide, so a member who can't see a workspace-scoped
        // incident must not be able to turn it into a public article either.
        if (incident == null || !incidentService.canSee(principal, incident)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Incident not found");
        }
        if (!"resolved".equalsIgnoreCase(incident.getStatus())) {
            throw ApiException.badRequest("Only resolved incidents can become knowledge-base articles");
        }
        if (articles.existsBySourceIncidentId(incidentId)) {
            throw ApiException.badRequest("A knowledge-base article already exists for this incident");
        }

        Map<String, Object> incidentCtx = new LinkedHashMap<>();
        incidentCtx.put("title", incident.getTitle());
        incidentCtx.put("severity", incident.getSeverity());
        incidentCtx.put("anomaly_description", incident.getAnomalyDescription());
        incidentCtx.put("root_cause", incident.getRootCause());
        incidentCtx.put("remediation_action", incident.getRemediationAction());
        incidentCtx.put("resolution_notes", incident.getResolutionNotes());

        Map<String, Object> gen = ml.kbExtract(Map.of("incident", incidentCtx));

        KbArticle article = new KbArticle(UUID.randomUUID(), principal.orgId(), incidentId, principal.userId());
        article.setTitle(str(gen.get("title"), incident.getTitle()));
        article.setCategory(str(gen.get("category"), "General"));
        article.setTags(joinTags(gen.get("tags")));
        article.setSymptoms(str(gen.get("symptoms"), incident.getAnomalyDescription()));
        article.setRootCause(str(gen.get("root_cause"), incident.getRootCause()));
        article.setSolution(str(gen.get("solution"), incident.getRemediationAction()));
        article.setPrevention(str(gen.get("prevention"), "Review after resolution."));
        article.setDifficulty(str(gen.get("difficulty"), "Intermediate"));
        articles.save(article);

        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "kb_article_created", "kb_article", article.getId().toString(), ip);
        return toView(article);
    }

    /**
     * Backfill: generate articles for every resolved incident that doesn't have one yet.
     * Each article is its own transaction (not nested under this method) so one failure
     * doesn't roll back articles already created earlier in the batch.
     */
    public Map<String, Object> generateFromAllResolved(AuthPrincipal principal, String ip) {
        // Same as single-article generation: a regular member's backfill only covers
        // incidents they can actually see (unscoped + their workspaces); admins cover
        // the whole org. generateFromIncident() re-checks visibility per incident below,
        // but filtering the pool here avoids wasted ML calls on incidents that would be
        // rejected anyway.
        List<Incident> pool = principal.isSuperAdmin()
                ? incidents.findAll(PageRequest.of(0, 100)).getContent()
                : incidents.findByOrgId(principal.orgId(), PageRequest.of(0, 100)).getContent()
                        .stream().filter(i -> incidentService.canSee(principal, i)).toList();

        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (Incident i : pool) {
            if (!"resolved".equalsIgnoreCase(i.getStatus())) {
                continue;
            }
            if (articles.existsBySourceIncidentId(i.getId())) {
                skipped++;
                continue;
            }
            try {
                generateFromIncident(principal, i.getId(), ip);
                created++;
            } catch (Exception e) {
                failed++;
            }
        }
        return Map.of("created", created, "skipped", skipped, "failed", failed);
    }

    private Map<String, Object> toView(KbArticle a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId().toString());
        m.put("sourceIncidentId", a.getSourceIncidentId() == null ? null : a.getSourceIncidentId().toString());
        m.put("title", a.getTitle());
        m.put("category", a.getCategory());
        m.put("tags", a.getTags() == null || a.getTags().isBlank()
                ? List.of() : List.of(a.getTags().split("\\s*,\\s*")));
        m.put("symptoms", a.getSymptoms());
        m.put("rootCause", a.getRootCause());
        m.put("solution", a.getSolution());
        m.put("prevention", a.getPrevention());
        m.put("difficulty", a.getDifficulty());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private static String str(Object v, String fallback) {
        String s = v == null ? null : String.valueOf(v);
        return (s == null || s.isBlank()) ? fallback : s;
    }

    @SuppressWarnings("unchecked")
    private static String joinTags(Object tags) {
        if (tags instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        }
        return "";
    }
}
