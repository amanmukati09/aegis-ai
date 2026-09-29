package ai.aegis.gateway.stream;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.incident.Incident;
import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.notification.NotificationService;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Streams: registered log/metric sources, and the real ingestion path for them.
 * POST /{id}/events is the "plug your real logs in" endpoint — it authenticates the same
 * way as everything else (JWT or an API key via AuthenticationFilter), so a log shipper
 * or agent just needs an API key, not a human session. No new infra: no Kafka, no Redis
 * buffer — ingested lines are pre-scanned in-process and, when they look anomalous, become
 * a real Incident (linked back to the stream via stream_id), which is what Live Monitor
 * and every other incident-driven feature already reads from.
 */
@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private static final int MAX_LINES_PER_PUSH = 5000;

    // Ordered severity signature -> band. First match wins; mirrors the same bands used by
    // the bulk-analysis segmenter and RL triage so a "critical" here means the same thing
    // everywhere else in the product.
    private static final List<Map.Entry<Pattern, String>> SIGNATURES = List.of(
            Map.entry(Pattern.compile("out of memory|oom|heap space|no space left|panic|fatal|segfault", Pattern.CASE_INSENSITIVE), "critical"),
            Map.entry(Pattern.compile("connection (refused|reset)|pool exhausted|\\b5\\d\\d\\b|service unavailable|crash(ed)?", Pattern.CASE_INSENSITIVE), "high"),
            Map.entry(Pattern.compile("\\berror\\b|exception|failed|timeout|denied", Pattern.CASE_INSENSITIVE), "medium"),
            Map.entry(Pattern.compile("\\bwarn(ing)?\\b|degraded|slow|retry", Pattern.CASE_INSENSITIVE), "low")
    );
    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "critical", 4, "high", 3, "medium", 2, "low", 1
    );
    // Auto-create an incident only when the batch's worst signal clears this bar — keeps
    // routine "info"-level traffic from spamming the incident list.
    private static final int AUTO_INCIDENT_THRESHOLD = SEVERITY_RANK.get("medium");

    private final StreamRepository repository;
    private final IncidentRepository incidents;
    private final AuditService audit;
    private final NotificationService notifications;

    public StreamController(StreamRepository repository, IncidentRepository incidents,
                            AuditService audit, NotificationService notifications) {
        this.repository = repository;
        this.incidents = incidents;
        this.audit = audit;
        this.notifications = notifications;
    }

    public record CreateRequest(@NotBlank String name, String description, String sourceType) {
    }

    public record StreamView(String id, String name, String description, String sourceType,
                             String status, long eventCount, OffsetDateTime lastEventAt,
                             OffsetDateTime createdAt) {
        static StreamView of(StreamRegistration s) {
            return new StreamView(s.getId().toString(), s.getName(), s.getDescription(),
                    s.getSourceType(), s.getStatus(), s.getEventCount(), s.getLastEventAt(), s.getCreatedAt());
        }
    }

    @GetMapping
    public List<StreamView> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return repository.findByOrgIdOrderByCreatedAtDesc(principal.orgId())
                .stream().map(StreamView::of).toList();
    }

    public record EventsRequest(@NotEmpty List<String> lines) {
    }

    public record EventsResponse(int linesReceived, String worstSeverity, boolean incidentCreated,
                                 String incidentId) {
    }

    /**
     * Push a batch of log lines into a stream. This is the real ingestion path: point a
     * log shipper, sidecar agent, or a simple curl/cron job at this URL with an API key
     * (Authorization: Bearer aegis_...) and it just works — no separate agent to install,
     * no message broker to stand up. A pre-scan classifies the worst signal in the batch;
     * if it's medium severity or worse, an Incident is created automatically (linked to
     * this stream) and the org is notified, the same way a manually-diagnosed incident
     * would be. Capped at MAX_LINES_PER_PUSH per call to keep this endpoint fast under load.
     */
    @PostMapping("/{id}/events")
    @Transactional
    public EventsResponse ingest(@AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID id, @Valid @RequestBody EventsRequest req,
                                 HttpServletRequest http) {
        StreamRegistration stream = repository.findByIdAndOrgId(id, principal.orgId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Stream not found"));
        if (!"active".equalsIgnoreCase(stream.getStatus())) {
            throw ApiException.badRequest("Stream is not active");
        }

        List<String> lines = req.lines().stream()
                .filter(l -> l != null && !l.isBlank())
                .limit(MAX_LINES_PER_PUSH)
                .toList();
        if (lines.isEmpty()) {
            throw ApiException.badRequest("No non-blank lines in this batch");
        }

        Classification result = classify(lines);
        OffsetDateTime now = OffsetDateTime.now();
        stream.recordEvent(lines.size(), now);
        repository.save(stream);

        String incidentId = null;
        boolean created = false;
        if (SEVERITY_RANK.get(result.severity) >= AUTO_INCIDENT_THRESHOLD) {
            Incident incident = new Incident(
                    UUID.randomUUID(), principal.orgId(), principal.userId(),
                    result.title(stream.getName()), result.severity,
                    String.join("\n", lines), result.sampleLine);
            incident.setStreamId(stream.getId());
            incidents.save(incident);
            incidentId = incident.getId().toString();
            created = true;

            notifications.create(principal.userId(), "incident",
                    "New incident from stream " + stream.getName(), incident.getTitle());
            audit.record(principal.orgId(), principal.userId(), principal.email(),
                    "incident_from_ingestion", "incident", incidentId, clientIp(http));
        }

        return new EventsResponse(lines.size(), result.severity, created, incidentId);
    }

    /** Worst-signal classification over a batch of lines — cheap, in-process, no ML round-trip. */
    private record Classification(String severity, String sampleLine) {
        String title(String streamName) {
            return "Anomaly on stream \"" + streamName + "\" (" + severity + ")";
        }
    }

    private static Classification classify(List<String> lines) {
        String worst = "low";
        String sample = lines.get(0);
        for (String line : lines) {
            for (Map.Entry<Pattern, String> sig : SIGNATURES) {
                if (sig.getKey().matcher(line).find()) {
                    String sev = sig.getValue();
                    if (SEVERITY_RANK.get(sev) > SEVERITY_RANK.get(worst)) {
                        worst = sev;
                        sample = line;
                    }
                    break; // first matching signature per line wins
                }
            }
        }
        return new Classification(worst, sample.length() > 300 ? sample.substring(0, 300) : sample);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public StreamView create(@AuthenticationPrincipal AuthPrincipal principal,
                             @Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        StreamRegistration s = new StreamRegistration(UUID.randomUUID(), principal.orgId(),
                req.name(), req.description(), req.sourceType(), principal.userId());
        repository.save(s);
        audit.record(principal.orgId(), principal.userId(), principal.email(),
                "stream_created", "stream", s.getId().toString(), clientIp(http));
        return StreamView.of(s);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    @Transactional
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        StreamRegistration s = repository.findByIdAndOrgId(id, principal.orgId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Stream not found"));
        repository.delete(s);
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank()) ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }
}
