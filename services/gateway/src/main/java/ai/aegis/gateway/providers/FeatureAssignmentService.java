package ai.aegis.gateway.providers;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Create/update/remove a Feature_Model_Assignment: an admin-configured
 * mapping from one of the 10 named Features to a specific Provider_Configuration
 * + Model_Entry pair, overriding the Organization's Global_Default for that
 * Feature (Req 5.3, 5.4). Absence of a row for a (org, feature) pair means
 * "use Global_Default" (Req 5.2, 5.5) — removal is how an admin reverts a
 * Feature back to the Global_Default.
 */
@Service
public class FeatureAssignmentService {

    /** The 10 named Features from design.md's Feature enum (Req 5.4). */
    public static final Set<String> VALID_FEATURES = Set.of(
            "copilot_chat", "diagnosis", "remediation", "runbook", "kb_extraction",
            "nl_to_sql", "triage", "sentiment", "code_fix", "rca_tree");

    private final FeatureModelAssignmentRepository assignments;
    private final ProviderConfigurationRepository configurations;
    private final ModelEntryRepository modelEntries;
    private final AuditService auditService;

    public FeatureAssignmentService(FeatureModelAssignmentRepository assignments,
                                     ProviderConfigurationRepository configurations,
                                     ModelEntryRepository modelEntries,
                                     AuditService auditService) {
        this.assignments = assignments;
        this.configurations = configurations;
        this.modelEntries = modelEntries;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------------

    public record AssignmentView(String feature, UUID providerConfigurationId, UUID modelEntryId) {
        static AssignmentView of(FeatureModelAssignment a) {
            return new AssignmentView(a.getFeature(), a.getProviderConfigurationId(), a.getModelEntryId());
        }
    }

    // ---------------------------------------------------------------------
    // Set / upsert
    // ---------------------------------------------------------------------

    @Transactional
    public AssignmentView setAssignment(AuthPrincipal principal, String feature,
                                         UUID providerConfigurationId, UUID modelEntryId) {
        UUID orgId = requireOrgId(principal);
        validateFeature(feature);
        validatePair(orgId, providerConfigurationId, modelEntryId);

        FeatureModelAssignment assignment = assignments.findByOrgIdAndFeature(orgId, feature)
                .orElse(null);
        if (assignment == null) {
            assignment = new FeatureModelAssignment(UUID.randomUUID(), orgId, feature,
                    providerConfigurationId, modelEntryId);
        } else {
            assignment.setProviderConfigurationId(providerConfigurationId);
            assignment.setModelEntryId(modelEntryId);
        }
        assignments.save(assignment);

        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "feature_assignment_set", "feature_model_assignment", feature, null);

        return AssignmentView.of(assignment);
    }

    // ---------------------------------------------------------------------
    // Remove
    // ---------------------------------------------------------------------

    @Transactional
    public void removeAssignment(AuthPrincipal principal, String feature) {
        UUID orgId = requireOrgId(principal);
        validateFeature(feature);

        assignments.deleteByOrgIdAndFeature(orgId, feature);

        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "feature_assignment_removed", "feature_model_assignment", feature, null);
    }

    // ---------------------------------------------------------------------
    // List
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, AssignmentView> listAssignments(AuthPrincipal principal) {
        UUID orgId = requireOrgId(principal);
        List<FeatureModelAssignment> rows = assignments.findByOrgId(orgId);
        Map<String, AssignmentView> result = new LinkedHashMap<>();
        for (FeatureModelAssignment row : rows) {
            result.put(row.getFeature(), AssignmentView.of(row));
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    private UUID requireOrgId(AuthPrincipal principal) {
        UUID orgId = principal.orgId();
        if (orgId == null) {
            throw ApiException.badRequest("Feature_Model_Assignments are organization-scoped");
        }
        return orgId;
    }

    private void validateFeature(String feature) {
        if (feature == null || !VALID_FEATURES.contains(feature)) {
            throw ApiException.badRequest("Unknown feature: " + feature);
        }
    }

    /**
     * The chosen Provider_Configuration/Model_Entry pair must belong to the
     * caller's org (or be platform-wide, usable as a fallback per Req 1.8),
     * and the Model_Entry must belong to that same Provider_Configuration.
     */
    private void validatePair(UUID orgId, UUID providerConfigurationId, UUID modelEntryId) {
        if (providerConfigurationId == null || modelEntryId == null) {
            throw ApiException.badRequest("providerConfigurationId and modelEntryId are required");
        }
        ProviderConfiguration config = configurations.findById(providerConfigurationId)
                .orElseThrow(() -> ApiException.badRequest("Unknown provider configuration: " + providerConfigurationId));

        boolean ownOrg = config.getOrgId() != null && config.getOrgId().equals(orgId);
        boolean platformWide = config.getOrgId() == null;
        if (!ownOrg && !platformWide) {
            throw ApiException.badRequest("Provider configuration does not belong to this organization");
        }

        modelEntries.findByIdAndProviderConfigurationId(modelEntryId, providerConfigurationId)
                .orElseThrow(() -> ApiException.badRequest("Model entry does not belong to the given provider configuration"));
    }
}
