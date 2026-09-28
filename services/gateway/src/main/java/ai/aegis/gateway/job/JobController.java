package ai.aegis.gateway.job;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.ml.MlClient;
import ai.aegis.gateway.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final ObjectMapper mapper;

    public JobController(AsyncJobService jobs, MlClient ml, ObjectMapper mapper) {
        this.jobs = jobs;
        this.ml = ml;
        this.mapper = mapper;
    }

    public record BulkRequest(@NotEmpty List<String> logs) {
    }

    public record JobView(String id, String type, String status, Object result, String error) {
    }

    @PostMapping("/bulk-analyze")
    public JobView submitBulk(@AuthenticationPrincipal AuthPrincipal principal,
                              @RequestBody BulkRequest req) throws Exception {
        String payload = mapper.writeValueAsString(Map.of("logs", req.logs()));
        AsyncJob job = jobs.submit(principal, "bulk-analyze", payload, p -> {
            try {
                Map<?, ?> parsed = mapper.readValue(p, Map.class);
                return ml.analyzeLogBatch(Map.of("lines", parsed.get("logs")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return new JobView(job.getId().toString(), job.getType(), job.getStatus(), null, null);
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
