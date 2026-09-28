package ai.aegis.gateway.stream;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A registered metric/log stream. Phase 5 maps the transactional fields; the jsonb
 * metric_schema and kafka_topic are populated when the (optional) Kafka ingestion
 * adapter is enabled in a later phase.
 */
@Entity
@Table(name = "stream_registrations")
public class StreamRegistration {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "source_type", nullable = false)
    private String sourceType = "http";

    @Column(nullable = false)
    private String status = "active";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected StreamRegistration() {
    }

    public StreamRegistration(UUID id, UUID orgId, String name, String description,
                              String sourceType, UUID createdBy) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.description = description;
        this.sourceType = sourceType == null ? "http" : sourceType;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
