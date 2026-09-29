package ai.aegis.gateway.job;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.incident.IncidentService;
import ai.aegis.gateway.incident.dto.IncidentDtos.IncidentView;
import ai.aegis.gateway.ml.MlClient;
import ai.aegis.gateway.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable async job API: submit long-running work (bulk log analysis) and poll for
 * status/result. Backed by the async_jobs table + virtual-thread executor.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final AsyncJobService jobs;
    private final MlClient ml;
    private final IncidentService incidents;
    private final ObjectMapper mapper;

    public JobController(AsyncJobService jobs, MlClient ml, IncidentService incidents, ObjectMapper mapper) {
        this.jobs = jobs;
        this.ml = ml;
        this.incidents = incidents;
        this.mapper = mapper;
    }

    public record BulkRequest(@NotEmpty List<String> logs, String source, Boolean createIncidents) {
    }

    public record JobView(String id, String type, String status, Object result, String error) {
    }

    /**
     * Bulk log analysis: segment the log stream into DISTINCT incidents and (by default)
     * create every detected incident as a real record. The job result carries the stats,
     * the created incidents, and enough to render a combined PDF report.
     */
    @PostMapping("/bulk-analyze")
    public JobView submitBulk(@AuthenticationPrincipal AuthPrincipal principal,
                              @RequestBody BulkRequest req) throws Exception {
        boolean create = req.createIncidents() == null || req.createIncidents();
        String source = req.source() == null ? "upload" : req.source();
        String payload = mapper.writeValueAsString(Map.of(
                "logs", req.logs(), "source", source, "create", create));

        AsyncJob job = jobs.submit(principal, "bulk-analyze", payload, p -> {
            try {
                Map<?, ?> parsed = mapper.readValue(p, Map.class);
                Object logs = parsed.get("logs");
                boolean doCreate = Boolean.TRUE.equals(parsed.get("create"));
                Object sourceVal = parsed.get("source");
                String src = sourceVal == null ? "upload" : String.valueOf(sourceVal);

                Map<String, Object> seg = ml.analyzeBulkIncidents(Map.of("lines", logs));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> detected =
                        seg.get("incidents") instanceof List<?> list
                                ? (List<Map<String, Object>>) (List<?>) list
                                : List.of();

                List<IncidentView> created = new ArrayList<>();
                if (doCreate && !detected.isEmpty()) {
                    created = incidents.createFromBulk(principal, detected, src, "bulk-job");
                }

                Map<String, Object> result = new java.util.LinkedHashMap<>(seg);
                result.put("source", src);
                result.put("created_count", created.size());
                result.put("created", created);
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return new JobView(job.getId().toString(), job.getType(), job.getStatus(), null, null);
    }

    /** Download a combined PDF report for a completed bulk-analysis job. */
    @GetMapping("/{id}/report.pdf")
    public ResponseEntity<byte[]> report(@AuthenticationPrincipal AuthPrincipal principal,
                                         @PathVariable UUID id) throws Exception {
        AsyncJob job = jobs.get(principal, id);
        if (job == null || job.getResult() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Job result not available");
        }
        Object analysis = mapper.readValue(job.getResult(), Object.class);
        byte[] pdf = ml.reportPdf(Map.of("title", "AegisAI Bulk Analysis Report", "analysis", analysis));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=bulk-analysis-report.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/{id}")
    public JobView get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) throws Exception {
        AsyncJob job = jobs.get(principal, id);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Job not found");
        }
        Object result = job.getResult() == null ? null : mapper.readValue(job.getResult(), Object.class);
        return new JobView(job.getId().toString(), job.getType(), job.getStatus(), result, job.getError());
    }
}
