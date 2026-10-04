package ai.aegis.gateway.incident;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.incident.dto.IncidentDtos.CreateRequest;
import ai.aegis.gateway.incident.dto.IncidentDtos.IncidentView;
import ai.aegis.gateway.incident.dto.IncidentDtos.PageResponse;
import ai.aegis.gateway.incident.dto.IncidentDtos.ResolveRequest;
import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.workspace.WorkspaceMemberRepository;
import ai.aegis.gateway.workspace.WorkspaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Incident business logic. Every read/write is scoped to the caller's organization;
 * a super_admin (no org) sees across all orgs. Mutations are audited.
 */
@Service
public class IncidentService {

    private final IncidentRepository repository;
    private final AuditService auditService;
    private final ai.aegis.gateway.notification.NotificationService notifications;
    private final ai.aegis.gateway.alert.AlertService alerts;
    private final ai.aegis.gateway.ml.MlClient ml;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository workspaceMembers;

    public IncidentService(IncidentRepository repository, AuditService auditService,
                           ai.aegis.gateway.notification.NotificationService notifications,
                           ai.aegis.gateway.alert.AlertService alerts,
                           ai.aegis.gateway.ml.MlClient ml,
                           WorkspaceRepository workspaces, WorkspaceMemberRepository workspaceMembers) {
        this.workspaces = workspaces;
        this.workspaceMembers = workspaceMembers;
        this.repository = repository;
        this.auditService = auditService;
        this.notifications = notifications;
        this.alerts = alerts;
        this.ml = ml;
    }

    // A UUID that can never match a real workspace row — used in place of an empty list
    // for JPQL/native "IN (:ids)" clauses, since Hibernate/Postgres reject an empty IN
    // list outright (native queries fail with a SQL syntax error). A member who belongs
    // to zero workspaces still needs to see the unscoped/shared incident pool.
    private static final UUID NO_WORKSPACES_SENTINEL = new UUID(0L, 0L);

    /**
     * Workspace IDs the caller can use to see workspace-scoped incidents. Admins bypass
     * workspace visibility entirely (represented by an empty list here — callers must
     * check {@link AuthPrincipal#bypassesWorkspaceVisibility()} first and use the
     * unrestricted repository methods instead of calling this for admins).
     */
    public java.util.List<UUID> visibleWorkspaceIds(AuthPrincipal principal) {
        java.util.List<UUID> ids = workspaceMembers.findWorkspaceIdsByUserId(principal.userId());
        return ids.isEmpty() ? java.util.List.of(NO_WORKSPACES_SENTINEL) : ids;
    }

    /**
     * Whether the caller is allowed to see this specific incident: admins see everything
     * in scope; everyone else sees unscoped incidents (the shared/general pool) plus
     * incidents scoped to a workspace they belong to. Public so other domains that
     * surface incident content (e.g. KB article generation) can apply the same rule
     * instead of re-deriving it.
     */
    public boolean canSee(AuthPrincipal principal, Incident incident) {
        if (principal.isSuperAdmin()) {
            return true;
        }
        if (!incident.getOrgId().equals(principal.orgId())) {
            return false;
        }
        if (principal.isOrgAdmin()) {
            return true;
        }
        UUID workspaceId = incident.getWorkspaceId();
        return workspaceId == null || workspaceMembers.existsByWorkspaceIdAndUserId(workspaceId, principal.userId());
    }

    /** Best-effort: embed the incident text and store the vector for similarity search. */
    private void embedAsync(UUID id, String text) {
        try {
            java.util.List<Double> vec = ml.embed(text);
            if (vec != null && !vec.isEmpty()) {
                String literal = "[" + vec.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(",")) + "]";
                repository.setEmbedding(id, literal);
            }
        } catch (Exception ignored) {
            // Similarity is a nice-to-have; never fail incident creation over it.
        }
    }

    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> similar(AuthPrincipal principal, UUID id) {
        Incident i = require(principal, id);
        java.util.List<Double> vec = ml.embed(
                (i.getTitle() == null ? "" : i.getTitle()) + " "
                        + (i.getAnomalyDescription() == null ? "" : i.getAnomalyDescription()));
        if (vec == null || vec.isEmpty()) {
            return java.util.List.of();
        }
        String literal = "[" + vec.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        // Results must respect the caller's workspace visibility too, not just the source
        // incident (require() above already gated the source; the candidates need the
        // same gate so a member can't discover a workspace-scoped incident's title/severity
        // through the "similar incidents" side-channel).
        var rows = principal.bypassesWorkspaceVisibility()
                ? repository.findSimilar(principal.orgId(), id, literal)
                : repository.findSimilarVisible(principal.orgId(), id, literal, visibleWorkspaceIds(principal));
        return rows.stream()
                .map(row -> {
                    // row = [id(text), title, severity, status, score(numeric)]
                    double score = row[4] == null ? 0.0 : ((Number) row[4]).doubleValue();
                    return java.util.Map.<String, Object>of(
                            "id", row[0] == null ? "" : row[0].toString(),
                            "title", row[1] == null ? "" : row[1].toString(),
                            "severity", row[2] == null ? "unknown" : row[2].toString(),
                            "status", row[3] == null ? "" : row[3].toString(),
                            "score", Math.round(score * 100) / 100.0);
                })
                .toList();
    }

    /** Build a lifecycle timeline for an incident. */
    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> timeline(AuthPrincipal principal, UUID id) {
        Incident i = require(principal, id);
        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        events.add(java.util.Map.of("type", "detected", "label", "Incident detected",
                "at", i.getDetectedAt() == null ? "" : i.getDetectedAt().toString()));
        if (i.getRootCause() != null && !i.getRootCause().isBlank()) {
            events.add(java.util.Map.of("type", "diagnosed", "label", "Root cause identified",
                    "detail", i.getRootCause()));
        }
        if (i.getAssignedTo() != null) {
            events.add(java.util.Map.of("type", "assigned", "label", "Incident claimed"));
        }
        if (i.getResolvedAt() != null) {
            events.add(java.util.Map.of("type", "resolved", "label", "Incident resolved",
                    "at", i.getResolvedAt().toString(),
                    "detail", i.getResolutionNotes() == null ? "" : i.getResolutionNotes()));
        }
        return events;
    }

    @Transactional(readOnly = true)
    public PageResponse list(AuthPrincipal principal, int page, int size) {
        return list(principal, page, size, null);
    }

    /**
     * Same listing, optionally scoped to a workspace. When no workspaceId is given, the
     * result set itself is workspace-visibility-aware for regular members: they see
     * unscoped incidents plus incidents in workspaces they belong to, NOT every incident
     * in the org. Admins (super_admin/org_admin) always see everything, since they
     * administer the whole org.
     */
    @Transactional(readOnly = true)
    public PageResponse list(AuthPrincipal principal, int page, int size, UUID workspaceId) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "detectedAt"));
        Page<Incident> result;
        if (workspaceId != null) {
            requireWorkspaceAccess(principal, workspaceId);
            result = repository.findByOrgIdAndWorkspaceId(principal.orgId(), workspaceId, pageable);
        } else if (principal.isSuperAdmin()) {
            result = repository.findAll(pageable);
        } else if (principal.isOrgAdmin()) {
            result = repository.findByOrgId(principal.orgId(), pageable);
        } else {
            result = repository.findVisibleByOrgId(principal.orgId(), visibleWorkspaceIds(principal), pageable);
        }
        return new PageResponse(
                result.map(IncidentView::of).getContent(),
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public IncidentView get(AuthPrincipal principal, UUID id) {
        return IncidentView.of(require(principal, id));
    }

    /**
     * Keyword search across title/description/root-cause, workspace-visibility-aware
     * exactly like list(): the Copilot's incident-search tool calls this, so a user
     * asking the chat about incidents can never learn about a workspace-scoped
     * incident they're not a member of.
     */
    @Transactional(readOnly = true)
    public java.util.List<IncidentView> search(AuthPrincipal principal, String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return java.util.List.of();
        }
        Pageable pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), 25),
                Sort.by(Sort.Direction.DESC, "detectedAt"));
        java.util.List<Incident> results = principal.bypassesWorkspaceVisibility()
                ? repository.searchByOrgId(principal.orgId(), q, pageable)
                : repository.searchVisibleByOrgId(principal.orgId(), visibleWorkspaceIds(principal), q, pageable);
        return results.stream().map(IncidentView::of).toList();
    }

    @Transactional
    public IncidentView create(AuthPrincipal principal, CreateRequest req, String ip) {
        UUID workspaceId = resolveWorkspaceForCreate(principal, req.workspaceId());
        Incident incident = new Incident(
                UUID.randomUUID(), principal.orgId(), principal.userId(),
                req.title(), req.severity(), req.rawLogs(), req.anomalyDescription());
        incident.setWorkspaceId(workspaceId);
        repository.save(incident);
        embedAsync(incident.getId(), req.title() + " " + (req.anomalyDescription() == null ? "" : req.anomalyDescription()));
        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "incident_created", "incident", incident.getId().toString(), ip);
        notifications.create(principal.userId(), "incident",
                "Incident created", incident.getTitle());
        alerts.dispatchIncident(incident.getTitle(), incident.getSeverity());
        return IncidentView.of(incident);
    }

    /** Validate a workspaceId on create: must belong to the org, and the caller must be a member. */
    private UUID resolveWorkspaceForCreate(AuthPrincipal principal, String workspaceIdStr) {
        if (workspaceIdStr == null || workspaceIdStr.isBlank()) {
            return null;
        }
        UUID workspaceId = parseWorkspaceId(workspaceIdStr);
        requireWorkspaceAccess(principal, workspaceId);
        return workspaceId;
    }

    private UUID parseWorkspaceId(String workspaceIdStr) {
        try {
            return UUID.fromString(workspaceIdStr);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid workspaceId");
        }
    }

    /**
     * Retag an existing incident's workspace (or clear it back to the shared/general
     * pool with a null/blank workspaceId). Admin-only: workspace membership controls who
     * can SEE an incident, not who can move it across that privacy boundary — moving an
     * incident into or out of a workspace is an org-management action, same tier as
     * managing workspace membership itself.
     */
    @Transactional
    public IncidentView setWorkspace(AuthPrincipal principal, UUID id, String workspaceIdStr, String ip) {
        if (!principal.bypassesWorkspaceVisibility()) {
            throw ApiException.forbidden("Only an org admin can move an incident between workspaces");
        }
        Incident incident = require(principal, id);
        UUID newWorkspaceId = (workspaceIdStr == null || workspaceIdStr.isBlank())
                ? null : parseWorkspaceId(workspaceIdStr);
        if (newWorkspaceId != null) {
            // Admins bypass member-of checks by design, but the workspace must still
            // belong to the same org — this 404s otherwise, same as create-time validation.
            workspaces.findByIdAndOrgId(newWorkspaceId, principal.orgId())
                    .orElseThrow(() -> ApiException.notFound("Workspace not found"));
        }
        incident.setWorkspaceId(newWorkspaceId);
        repository.save(incident);
        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "incident_workspace_changed", "incident", id.toString(), ip);
        return IncidentView.of(incident);
    }

    /** A workspace filter/tag is only usable by org members who actually belong to it. */
    private void requireWorkspaceAccess(AuthPrincipal principal, UUID workspaceId) {
        workspaces.findByIdAndOrgId(workspaceId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Workspace not found"));
        if (!principal.isSuperAdmin() && !workspaceMembers.existsByWorkspaceIdAndUserId(workspaceId, principal.userId())) {
            throw ApiException.forbidden("You are not a member of this workspace");
        }
    }

    // Root-causing every incident in a large bulk upload would fan out one ML round-trip
    // per detected incident; on a busy box that's the fastest way to starve the Hikari
    // pool and the ML sidecar. Diagnose only the most severe incidents in a batch — the
    // rest still exist as incidents (segmenter-derived title/severity/evidence), just
    // without an AI root cause/remediation until someone opens them and clicks Diagnose.
    private static final int MAX_AUTO_DIAGNOSE_PER_BATCH = 10;
    private static final java.util.Map<String, Integer> SEVERITY_ORDER =
            java.util.Map.of("critical", 4, "high", 3, "medium", 2, "low", 1);

    /**
     * Create every incident detected by bulk log segmentation, in one org-scoped batch.
     * Unlike the previous app (which capped persistence at 3 while reporting more), this
     * persists ALL detected incidents. The most severe ones (up to
     * MAX_AUTO_DIAGNOSE_PER_BATCH) are auto-diagnosed the same way a single pasted-log
     * diagnosis works — root cause + remediation, not just "an anomaly was found" — so a
     * bulk upload isn't a second-class citizen next to manual diagnosis. Notifications/
     * alerts are collapsed into a single batch summary to avoid spamming one-per-incident.
     */
    @Transactional
    public java.util.List<IncidentView> createFromBulk(
            AuthPrincipal principal, java.util.List<java.util.Map<String, Object>> detected,
            String sourceLabel, String ip) {
        java.util.List<Incident> createdIncidents = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> d : detected) {
            String title = str(d.get("title"), "Detected incident");
            String severity = str(d.get("severity"), "medium");
            String description = str(d.get("description"), "");
            String evidence = d.get("evidence") instanceof java.util.List<?> ev
                    ? ev.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("\n"))
                    : "";
            String rawLogs = "Bulk analysis from: " + (sourceLabel == null ? "upload" : sourceLabel)
                    + (evidence.isBlank() ? "" : "\n\nEvidence:\n" + evidence);

            Incident incident = new Incident(
                    UUID.randomUUID(), principal.orgId(), principal.userId(),
                    title, severity, rawLogs, description);
            repository.save(incident);
            embedAsync(incident.getId(), title + " " + description);
            createdIncidents.add(incident);
        }

        autoDiagnoseMostSevere(createdIncidents);

        if (!createdIncidents.isEmpty()) {
            auditService.record(principal.orgId(), principal.userId(), principal.email(),
                    "incidents_bulk_created", "incident", createdIncidents.size() + " incidents", ip);
            notifications.create(principal.userId(), "incident",
                    "Bulk analysis complete",
                    createdIncidents.size() + " incidents created from " + (sourceLabel == null ? "upload" : sourceLabel));
        }
        return createdIncidents.stream().map(IncidentView::of).toList();
    }

    /** Root-cause + remediate the worst incidents from a bulk batch, best-effort. */
    private void autoDiagnoseMostSevere(java.util.List<Incident> createdIncidents) {
        createdIncidents.stream()
                .sorted(java.util.Comparator.comparingInt(
                        (Incident i) -> SEVERITY_ORDER.getOrDefault(
                                i.getSeverity() == null ? "" : i.getSeverity().toLowerCase(), 0))
                        .reversed())
                .limit(MAX_AUTO_DIAGNOSE_PER_BATCH)
                .forEach(this::autoDiagnoseOne);
    }

    private void autoDiagnoseOne(Incident incident) {
        try {
            java.util.List<String> logs = incident.getRawLogs() == null
                    ? java.util.List.of() : java.util.Arrays.asList(incident.getRawLogs().split("\n"));
            java.util.Map<String, Object> anomalyMap = java.util.Map.of(
                    "anomaly_type", incident.getTitle() == null ? "unknown" : incident.getTitle(),
                    "severity", incident.getSeverity() == null ? "medium" : incident.getSeverity(),
                    "affected_component", "unknown",
                    "description", incident.getAnomalyDescription() == null ? "" : incident.getAnomalyDescription());

            var diagnosis = ml.diagnose(new ai.aegis.gateway.ml.MlDtos.DiagnoseRequest(anomalyMap, logs, null, null));
            java.util.Map<String, Object> rootCauseMap = java.util.Map.of(
                    "root_cause", diagnosis.root_cause() == null ? "" : diagnosis.root_cause());
            var remediation = ml.suggestRemediation(
                    new ai.aegis.gateway.ml.MlDtos.RemediationRequest(anomalyMap, rootCauseMap, null, null));

            incident.setRootCause(diagnosis.root_cause());
            incident.setRemediationAction(String.join("; ", remediation.immediate_actions()));
            incident.setRemediationStatus("pending");
            repository.save(incident);
        } catch (Exception e) {
            // Auto-diagnosis is best-effort — a slow/unavailable ML sidecar must never
            // fail the whole bulk job; the incident still exists without a root cause.
        }
    }

    private static String str(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }

    @Transactional
    public IncidentView resolve(AuthPrincipal principal, UUID id, ResolveRequest req, String ip) {
        Incident incident = require(principal, id);
        incident.setStatus("resolved");
        incident.setResolvedAt(OffsetDateTime.now());
        incident.setResolvedBy(principal.userId());
        incident.setResolutionNotes(req == null ? null : req.resolutionNotes());
        incident.setRemediationStatus("completed");
        repository.save(incident);
        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "incident_resolved", "incident", id.toString(), ip);
        return IncidentView.of(incident);
    }

    @Transactional
    public void delete(AuthPrincipal principal, UUID id, String ip) {
        Incident incident = require(principal, id);
        repository.delete(incident);
        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "incident_deleted", "incident", id.toString(), ip);
    }

    /** Claim (self-assign) an incident. Any org member may claim within their org. */
    @Transactional
    public IncidentView claim(AuthPrincipal principal, UUID id, String ip) {
        Incident incident = require(principal, id);
        incident.setAssignedTo(principal.userId());
        repository.save(incident);
        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "incident_claimed", "incident", id.toString(), ip);
        return IncidentView.of(incident);
    }

    /** Fetch an org-scoped incident's logs+context for an ML advanced call. */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> mlContext(AuthPrincipal principal, UUID id) {
        Incident i = require(principal, id);
        java.util.List<String> logs = i.getRawLogs() == null
                ? java.util.List.of()
                : java.util.Arrays.asList(i.getRawLogs().split("\n"));
        return java.util.Map.of(
                "anomaly", java.util.Map.of(
                        "severity", i.getSeverity() == null ? "medium" : i.getSeverity(),
                        "description", i.getAnomalyDescription() == null ? "" : i.getAnomalyDescription()),
                "root_cause", java.util.Map.of("root_cause", i.getRootCause() == null ? "" : i.getRootCause()),
                "logs", logs
        );
    }

    /**
     * Resolve an incident the caller is allowed to see, or 404. 404 (not 403) is used for
     * both "doesn't exist" and "exists but you can't see it" so a non-member can't probe
     * incident IDs to learn whether a workspace-scoped incident exists.
     */
    private Incident require(AuthPrincipal principal, UUID id) {
        Incident incident = principal.isSuperAdmin()
                ? repository.findById(id).orElse(null)
                : repository.findByIdAndOrgId(id, principal.orgId()).orElse(null);
        if (incident == null || !canSee(principal, incident)) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Incident not found");
        }
        return incident;
    }
}
