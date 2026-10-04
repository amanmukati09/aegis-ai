package ai.aegis.gateway.providers;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Set/get the Global_Default — the Provider_Configuration + Model_Entry pair
 * used by any Feature without an explicit Feature_Model_Assignment (Req 5.1,
 * 5.6). Two scopes exist: an org-scoped row (at most one per org, PK is
 * {@code org_id} on {@code global_default_assignments} so writes are a true
 * upsert) and a platform-wide singleton row ({@code platform_default_assignment},
 * PK is the always-TRUE {@code singleton} column) used as a fallback for
 * organizations with no Global_Default of their own (Req 1.8, 5.6).
 */
@Service
public class GlobalDefaultService {

    private final GlobalDefaultAssignmentRepository globalDefaults;
    private final PlatformDefaultAssignmentRepository platformDefaults;
    private final ProviderConfigurationRepository configurations;
    private final ModelEntryRepository modelEntries;
    private final AuditService auditService;

    public GlobalDefaultService(GlobalDefaultAssignmentRepository globalDefaults,
                                 PlatformDefaultAssignmentRepository platformDefaults,
                                 ProviderConfigurationRepository configurations,
                                 ModelEntryRepository modelEntries,
                                 AuditService auditService) {
        this.globalDefaults = globalDefaults;
        this.platformDefaults = platformDefaults;
        this.configurations = configurations;
        this.modelEntries = modelEntries;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------------

    public record GlobalDefaultView(UUID providerConfigurationId, UUID modelEntryId) {
        static GlobalDefaultView of(GlobalDefaultAssignment a) {
            return new GlobalDefaultView(a.getProviderConfigurationId(), a.getModelEntryId());
        }

        static GlobalDefaultView of(PlatformDefaultAssignment a) {
            return new GlobalDefaultView(a.getProviderConfigurationId(), a.getModelEntryId());
        }
    }

    // ---------------------------------------------------------------------
    // Org-scoped Global_Default
    // ---------------------------------------------------------------------

    @Transactional
    public GlobalDefaultView setOrgDefault(AuthPrincipal principal, UUID providerConfigurationId, UUID modelEntryId) {
        UUID orgId = principal.orgId();
        if (orgId == null) {
            throw ApiException.badRequest("A platform-wide caller must use setPlatformDefault instead");
        }
        validatePair(orgId, providerConfigurationId, modelEntryId);

        GlobalDefaultAssignment assignment = globalDefaults.findByOrgId(orgId)
                .orElse(null);
        if (assignment == null) {
            assignment = new GlobalDefaultAssignment(orgId, providerConfigurationId, modelEntryId);
        } else {
            assignment.setProviderConfigurationId(providerConfigurationId);
            assignment.setModelEntryId(modelEntryId);
        }
        globalDefaults.save(assignment);

        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "global_default_set", "global_default_assignment", orgId.toString(), null);

        return GlobalDefaultView.of(assignment);
    }

    @Transactional(readOnly = true)
    public Optional<GlobalDefaultView> getOrgDefault(AuthPrincipal principal) {
        UUID orgId = principal.orgId();
        if (orgId == null) {
            return Optional.empty();
        }
        return globalDefaults.findByOrgId(orgId).map(GlobalDefaultView::of);
    }

    // ---------------------------------------------------------------------
    // Platform-wide singleton Global_Default
    // ---------------------------------------------------------------------

    @Transactional
    public GlobalDefaultView setPlatformDefault(AuthPrincipal principal, UUID providerConfigurationId, UUID modelEntryId) {
        if (!principal.isSuperAdmin()) {
            throw ApiException.forbidden("Only a SUPER_ADMIN may set the platform-wide Global_Default");
        }
        validatePair(null, providerConfigurationId, modelEntryId);

        PlatformDefaultAssignment assignment = platformDefaults.findById(Boolean.TRUE)
                .orElse(null);
        if (assignment == null) {
            assignment = new PlatformDefaultAssignment(providerConfigurationId, modelEntryId);
        } else {
            assignment.setProviderConfigurationId(providerConfigurationId);
            assignment.setModelEntryId(modelEntryId);
        }
        platformDefaults.save(assignment);

        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "global_default_set", "platform_default_assignment", "platform", null);

        return GlobalDefaultView.of(assignment);
    }

    @Transactional(readOnly = true)
    public Optional<GlobalDefaultView> getPlatformDefault() {
        return platformDefaults.findById(Boolean.TRUE).map(GlobalDefaultView::of);
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    /**
     * The chosen Provider_Configuration/Model_Entry pair must belong to the
     * target org (or be platform-wide, usable as a fallback by every org per
     * Req 1.8), and the Model_Entry must belong to that same Provider_Configuration.
     */
    private void validatePair(UUID orgId, UUID providerConfigurationId, UUID modelEntryId) {
        if (providerConfigurationId == null || modelEntryId == null) {
            throw ApiException.badRequest("providerConfigurationId and modelEntryId are required");
        }
        ProviderConfiguration config = configurations.findById(providerConfigurationId)
                .orElseThrow(() -> ApiException.badRequest("Unknown provider configuration: " + providerConfigurationId));

        boolean ownOrg = orgId != null && config.getOrgId() != null && config.getOrgId().equals(orgId);
        boolean platformWide = config.getOrgId() == null;
        if (!ownOrg && !platformWide) {
            throw ApiException.badRequest("Provider configuration does not belong to this organization");
        }

        ModelEntry model = modelEntries.findByIdAndProviderConfigurationId(modelEntryId, providerConfigurationId)
                .orElseThrow(() -> ApiException.badRequest("Model entry does not belong to the given provider configuration"));
        // model is validated to exist and belong to the configuration; no further use needed here.
        if (model.getProviderConfigurationId() == null) {
            throw ApiException.badRequest("Model entry does not belong to the given provider configuration");
        }
    }
}
