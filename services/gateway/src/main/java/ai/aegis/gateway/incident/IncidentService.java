package ai.aegis.gateway.incident;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.incident.dto.IncidentDtos.CreateRequest;
import ai.aegis.gateway.incident.dto.IncidentDtos.IncidentView;
import ai.aegis.gateway.incident.dto.IncidentDtos.PageResponse;
import ai.aegis.gateway.incident.dto.IncidentDtos.ResolveRequest;
import ai.aegis.gateway.security.AuthPrincipal;
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

    public IncidentService(IncidentRepository repository, AuditService auditService,
                           ai.aegis.gateway.notification.NotificationService notifications,
                           ai.aegis.gateway.alert.AlertService alerts) {
        this.repository = repository;
        this.auditService = auditService;
        this.notifications = notifications;
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public PageResponse list(AuthPrincipal principal, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "detectedAt"));
        Page<Incident> result = principal.isSuperAdmin()
                ? repository.findAll(pageable)
                : repository.findByOrgId(principal.orgId(), pageable);
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

    @Transactional
    public IncidentView create(AuthPrincipal principal, CreateRequest req, String ip) {
        Incident incident = new Incident(
                UUID.randomUUID(), principal.orgId(), principal.userId(),
                req.title(), req.severity(), req.rawLogs(), req.anomalyDescription());
        repository.save(incident);
        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "incident_created", "incident", incident.getId().toString(), ip);
        notifications.create(principal.userId(), "incident",
                "Incident created", incident.getTitle());
        alerts.dispatchIncident(incident.getTitle(), incident.getSeverity());
        return IncidentView.of(incident);
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

    /** Resolve an incident the caller is allowed to see, or 404. */
    private Incident require(AuthPrincipal principal, UUID id) {
        Incident incident = principal.isSuperAdmin()
                ? repository.findById(id).orElse(null)
                : repository.findByIdAndOrgId(id, principal.orgId()).orElse(null);
        if (incident == null) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Incident not found");
        }
        return incident;
    }
}
