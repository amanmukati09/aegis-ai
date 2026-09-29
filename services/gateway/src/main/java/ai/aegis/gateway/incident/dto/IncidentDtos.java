package ai.aegis.gateway.incident.dto;

import ai.aegis.gateway.incident.Incident;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;

/** Request/response DTOs for incidents. */
public final class IncidentDtos {

    private IncidentDtos() {
    }

    public record CreateRequest(
            @NotBlank String title,
            String severity,
            String rawLogs,
            String anomalyDescription,
            String workspaceId
    ) {
    }

    public record ResolveRequest(String resolutionNotes) {
    }

    /** null/blank workspaceId clears the tag, moving the incident back to the shared pool. */
    public record SetWorkspaceRequest(String workspaceId) {
    }

    public record IncidentView(
            String id,
            String orgId,
            String userId,
            String assignedTo,
            String workspaceId,
            String title,
            String status,
            String severity,
            String anomalyDescription,
            String rootCause,
            String remediationAction,
            String remediationStatus,
            String resolutionNotes,
            OffsetDateTime detectedAt,
            OffsetDateTime resolvedAt
    ) {
        public static IncidentView of(Incident i) {
            return new IncidentView(
                    i.getId().toString(),
                    i.getOrgId().toString(),
                    i.getUserId() == null ? null : i.getUserId().toString(),
                    i.getAssignedTo() == null ? null : i.getAssignedTo().toString(),
                    i.getWorkspaceId() == null ? null : i.getWorkspaceId().toString(),
                    i.getTitle(),
                    i.getStatus(),
                    i.getSeverity(),
                    i.getAnomalyDescription(),
                    i.getRootCause(),
                    i.getRemediationAction(),
                    i.getRemediationStatus(),
                    i.getResolutionNotes(),
                    i.getDetectedAt(),
                    i.getResolvedAt()
            );
        }
    }

    public record PageResponse(
            List<IncidentView> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
    }
}
