package ai.aegis.gateway.job;

import ai.aegis.gateway.security.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Function;

/**
 * Durable job runner. submit() persists a pending job and kicks off async execution on
 * virtual threads; the work function produces a result object that is stored back on the
 * job. Callers poll get() for status/result.
 */
@Service
public class AsyncJobService {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobService.class);

    private final AsyncJobRepository repository;
    private final ObjectMapper mapper;

    public AsyncJobService(AsyncJobRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public AsyncJob submit(AuthPrincipal principal, String type, String payloadJson,
                           Function<String, Object> work) {
        AsyncJob job = new AsyncJob(UUID.randomUUID(), principal.orgId(), principal.userId(), type, payloadJson);
        repository.save(job);
        runAsync(job.getId(), work);
        return job;
    }

    @Async("jobExecutor")
    public void runAsync(UUID jobId, Function<String, Object> work) {
        setStatus(jobId, "running", null, null);
        try {
            AsyncJob job = repository.findById(jobId).orElseThrow();
            Object result = work.apply(job.getPayload());
            setStatus(jobId, "completed", mapper.writeValueAsString(result), null);
        } catch (Exception e) {
            log.error("Job {} failed: {}", jobId, e.getMessage());
            setStatus(jobId, "failed", null, e.getMessage());
        }
    }

    @Transactional
    protected void setStatus(UUID jobId, String status, String result, String error) {
        repository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            if (result != null) job.setResult(result);
            if (error != null) job.setError(error);
            repository.save(job);
        });
    }

    @Transactional(readOnly = true)
    public AsyncJob get(AuthPrincipal principal, UUID id) {
        return repository.findByIdAndOrgId(id, principal.orgId()).orElse(null);
    }
}
