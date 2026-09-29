package ai.aegis.gateway.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Incident entity mapping the V1 `incidents` table. Phase 2 maps the transactional
 * subset; ML-only columns (embedding vector, anomaly_details jsonb, causal_graph_id,
 * rl_*) are added in later phases when those features arrive.
 */
@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "stream_id")
    private UUID streamId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "user_id")
    private UUID userId;

    private String title;

    @Column(name = "raw_logs")
    private String rawLogs;

    @Column(nullable = false)
    private String status = "open";

    private String severity;

    @Column(name = "anomaly_description")
    private String anomalyDescription;

    @Column(name = "root_cause")
    private String rootCause;

    @Column(name = "remediation_action")
    private String remediationAction;

    @Column(name = "remediation_status", nullable = false)
    private String remediationStatus = "pending";

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolution_notes")
    private String resolutionNotes;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt = OffsetDateTime.now();

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected Incident() {
    }

    public Incident(UUID id, UUID orgId, UUID userId, String title, String severity,
                    String rawLogs, String anomalyDescription) {
        this.id = id;
        this.orgId = orgId;
        this.userId = userId;
        this.title = title;
        this.severity = severity;
        this.rawLogs = rawLogs;
        this.anomalyDescription = anomalyDescription;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getStreamId() {
        return streamId;
    }

    public void setStreamId(UUID streamId) {
        this.streamId = streamId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(UUID assignedTo) {
        this.assignedTo = assignedTo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRawLogs() {
        return rawLogs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getAnomalyDescription() {
        return anomalyDescription;
    }

    public void setAnomalyDescription(String anomalyDescription) {
        this.anomalyDescription = anomalyDescription;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getRemediationAction() {
        return remediationAction;
    }

    public void setRemediationAction(String remediationAction) {
        this.remediationAction = remediationAction;
    }

    public String getRemediationStatus() {
        return remediationStatus;
    }

    public void setRemediationStatus(String remediationStatus) {
        this.remediationStatus = remediationStatus;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(UUID resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public void setResolutionNotes(String resolutionNotes) {
        this.resolutionNotes = resolutionNotes;
    }

    public OffsetDateTime getDetectedAt() {
        return detectedAt;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(OffsetDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
