package ai.aegis.gateway.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A durable background job (replaces the original in-memory queue). Survives restarts;
 * status is polled via the jobs API.
 */
@Entity
@Table(name = "async_jobs")
public class AsyncJob {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status = "pending"; // pending|running|completed|failed

    @Column(columnDefinition = "text")
    private String payload;

    @Column(columnDefinition = "text")
    private String result;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected AsyncJob() {
    }

    public AsyncJob(UUID id, UUID orgId, UUID userId, String type, String payload) {
        this.id = id;
        this.orgId = orgId;
        this.userId = userId;
        this.type = type;
        this.payload = payload;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPayload() {
        return payload;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
