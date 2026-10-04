package ai.aegis.gateway.incident;

import ai.aegis.gateway.incident.dto.IncidentDtos.CreateRequest;
import ai.aegis.gateway.incident.dto.IncidentDtos.IncidentView;
import ai.aegis.gateway.incident.dto.IncidentDtos.PageResponse;
import ai.aegis.gateway.incident.dto.IncidentDtos.ResolveRequest;
import ai.aegis.gateway.incident.dto.IncidentDtos.SetWorkspaceRequest;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService service;
    private final ai.aegis.gateway.ml.MlClient ml;

    public IncidentController(IncidentService service, ai.aegis.gateway.ml.MlClient ml) {
        this.service = service;
        this.ml = ml;
    }

    @GetMapping
    public PageResponse list(@AuthenticationPrincipal AuthPrincipal principal,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             @RequestParam(required = false) java.util.UUID workspaceId) {
        return service.list(principal, page, size, workspaceId);
    }

    @GetMapping("/{id}")
    public IncidentView get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return service.get(principal, id);
    }

    /**
     * Keyword search, workspace-visibility-aware. Used by the Copilot's agent tools
     * (called back from the ML sidecar with the user's own forwarded credentials) as
     * well as any future in-app search box — same access rules as browsing the list.
     */
    @GetMapping("/search")
    public java.util.List<IncidentView> search(@AuthenticationPrincipal AuthPrincipal principal,
                                               @RequestParam("q") String q,
                                               @RequestParam(defaultValue = "10") int limit) {
        return service.search(principal, q, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentView create(@AuthenticationPrincipal AuthPrincipal principal,
                               @Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        return service.create(principal, req, clientIp(http));
    }

    @PostMapping("/{id}/resolve")
    public IncidentView resolve(@AuthenticationPrincipal AuthPrincipal principal,
                                @PathVariable UUID id, @RequestBody(required = false) ResolveRequest req,
                                HttpServletRequest http) {
        return service.resolve(principal, id, req, clientIp(http));
    }

    @PostMapping("/{id}/claim")
    public IncidentView claim(@AuthenticationPrincipal AuthPrincipal principal,
                              @PathVariable UUID id, HttpServletRequest http) {
        return service.claim(principal, id, clientIp(http));
    }

    /** Move an incident into/out of a workspace after the fact. Admin-only. */
    @PutMapping("/{id}/workspace")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    public IncidentView setWorkspace(@AuthenticationPrincipal AuthPrincipal principal,
                                     @PathVariable UUID id, @RequestBody SetWorkspaceRequest req,
                                     HttpServletRequest http) {
        return service.setWorkspace(principal, id, req.workspaceId(), clientIp(http));
    }

    @PostMapping("/{id}/rca-tree")
    public java.util.Map<String, Object> rcaTree(@AuthenticationPrincipal AuthPrincipal principal,
                                                 @PathVariable UUID id) {
        return ml.rcaTree(service.mlContext(principal, id));
    }

    @PostMapping("/{id}/code-fix")
    public java.util.Map<String, Object> codeFix(@AuthenticationPrincipal AuthPrincipal principal,
                                                 @PathVariable UUID id) {
        return ml.codeFix(service.mlContext(principal, id));
    }

    @PostMapping("/{id}/runbook")
    public java.util.Map<String, Object> runbook(@AuthenticationPrincipal AuthPrincipal principal,
                                                 @PathVariable UUID id) {
        var ctx = service.mlContext(principal, id);
        return ml.runbook(java.util.Map.of("incident", ctx));
    }

    @GetMapping("/{id}/similar")
    public java.util.List<java.util.Map<String, Object>> similar(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return service.similar(principal, id);
    }

    @GetMapping("/{id}/timeline")
    public java.util.List<java.util.Map<String, Object>> timeline(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return service.timeline(principal, id);
    }

    @PostMapping("/{id}/report.pdf")
    public ResponseEntity<byte[]> reportPdf(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable UUID id) {
        var view = service.get(principal, id);
        byte[] pdf = ml.reportPdf(java.util.Map.of(
                "title", "Incident Report: " + (view.title() == null ? id.toString() : view.title()),
                "analysis", java.util.Map.of(
                        "summary", java.util.Map.of(
                                "description", view.anomalyDescription() == null ? "" : view.anomalyDescription(),
                                "severity", view.severity() == null ? "unknown" : view.severity()),
                        "total_lines", 0)));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=incident-report.pdf")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                       HttpServletRequest http) {
        service.delete(principal, id, clientIp(http));
    }

    @GetMapping("/export/csv")
    public ResponseEntity<StreamingResponseBody> exportCsv(@AuthenticationPrincipal AuthPrincipal principal) {
        // Stream a reasonable page of incidents as CSV (avoids loading everything).
        PageResponse data = service.list(principal, 0, 100);
        StreamingResponseBody body = out -> {
            StringBuilder sb = new StringBuilder("id,title,status,severity,detected_at,resolved_at\n");
            for (IncidentView i : data.items()) {
                sb.append(csv(i.id())).append(',')
                  .append(csv(i.title())).append(',')
                  .append(csv(i.status())).append(',')
                  .append(csv(i.severity())).append(',')
                  .append(csv(i.detectedAt() == null ? "" : i.detectedAt().toString())).append(',')
                  .append(csv(i.resolvedAt() == null ? "" : i.resolvedAt().toString())).append('\n');
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        };
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=incidents.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
