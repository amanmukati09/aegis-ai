package ai.aegis.gateway.providers;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Create/replace an ordered Priority_List — the admin-defined failover
 * sequence of Provider_Configuration + Model_Entry pairs (Req 4.1, 4.2).
 * Scoped per (org, feature) pair, where {@code feature == null} (represented
 * at this layer by the {@link #GLOBAL_SCOPE} sentinel, {@code "_global"},
 * matching design.md's REST payload shape) means the list applies to every
 * Feature that doesn't have its own feature-specific list, per the
 * {@code priority_list_entries.feature IS NULL} semantics in the schema.
 * Platform-wide (org-null) lists follow the same SUPER_ADMIN-only write rule
 * as {@link ProviderConfigService}'s platform-wide rows.
 */
@Service
public class PriorityListService {

    /** Sentinel feature value meaning "applies to all features" (feature IS NULL in the schema). */
    public static final String GLOBAL_SCOPE = "_global";

    private final PriorityListEntryRepository entries;
    private final ProviderConfigurationRepository configurations;
    private final ModelEntryRepository modelEntries;
    private final AuditService auditService;

    public PriorityListService(PriorityListEntryRepository entries,
                                ProviderConfigurationRepository configurations,
                                ModelEntryRepository modelEntries,
                                AuditService auditService) {
        this.entries = entries;
        this.configurations = configurations;
        this.modelEntries = modelEntries;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------------

    public record PriorityPair(UUID providerConfigurationId, UUID modelEntryId) {
    }

    public record PriorityListEntryView(UUID providerConfigurationId, UUID modelEntryId, int priorityOrder) {
        static PriorityListEntryView of(PriorityListEntry e) {
            return new PriorityListEntryView(e.getProviderConfigurationId(), e.getModelEntryId(), e.getPriorityOrder());
        }
    }

    // ---------------------------------------------------------------------
    // Set / replace
    // ---------------------------------------------------------------------

    @Transactional
    public List<PriorityListEntryView> setPriorityList(AuthPrincipal principal, String feature,
                                                         List<PriorityPair> orderedPairs) {
        UUID orgId = resolveScopeOrgId(principal, feature);
        String normalizedFeature = normalizeFeature(feature);

        if (orderedPairs == null) {
            orderedPairs = List.of();
        }
        for (PriorityPair pair : orderedPairs) {
            validatePair(orgId, pair);
        }

        // Replace the entire ordered list for this (org, feature) scope: delete
        // all existing rows, then insert the new ones with priority_order 1..N.
        if (normalizedFeature == null) {
            entries.deleteByOrgIdAndFeatureIsNull(orgId);
        } else {
            entries.deleteByOrgIdAndFeature(orgId, normalizedFeature);
        }

        List<PriorityListEntry> created = new ArrayList<>();
        int order = 1;
        for (PriorityPair pair : orderedPairs) {
            PriorityListEntry entry = new PriorityListEntry(UUID.randomUUID(), orgId, normalizedFeature,
                    pair.providerConfigurationId(), pair.modelEntryId(), order++);
            entries.save(entry);
            created.add(entry);
        }

        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "priority_list_set", "priority_list_entries",
                normalizedFeature == null ? GLOBAL_SCOPE : normalizedFeature, null);

        return created.stream().map(PriorityListEntryView::of).toList();
    }

    // ---------------------------------------------------------------------
    // Get
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PriorityListEntryView> getPriorityList(AuthPrincipal principal, String feature) {
        UUID orgId = resolveScopeOrgId(principal, feature);
        String normalizedFeature = normalizeFeature(feature);

        List<PriorityListEntry> rows;
        if (orgId == null) {
            rows = normalizedFeature == null
                    ? entries.findByOrgIdIsNullAndFeatureIsNullOrderByPriorityOrderAsc()
                    : entries.findByOrgIdIsNullAndFeatureOrderByPriorityOrderAsc(normalizedFeature);
        } else {
            rows = normalizedFeature == null
                    ? entries.findByOrgIdAndFeatureIsNullOrderByPriorityOrderAsc(orgId)
                    : entries.findByOrgIdAndFeatureOrderByPriorityOrderAsc(orgId, normalizedFeature);
        }
        return rows.stream().map(PriorityListEntryView::of).toList();
    }

    // ---------------------------------------------------------------------
    // Scope / validation helpers
    // ---------------------------------------------------------------------

    private String normalizeFeature(String feature) {
        if (feature == null || feature.isBlank() || GLOBAL_SCOPE.equals(feature)) {
            return null;
        }
        if (!FeatureAssignmentService.VALID_FEATURES.contains(feature)) {
            throw ApiException.badRequest("Unknown feature: " + feature);
        }
        return feature;
    }

    /**
     * Resolves which org this Priority_List write/read targets. Org-scoped
     * callers (ORG_ADMIN/SUPER_ADMIN with an orgId) always target their own
     * org. A caller with no org (platform-wide) may only write/read the
     * platform-wide (org_id NULL) list, and only if SUPER_ADMIN -- the same
     * SUPER_ADMIN-only write rule as ProviderConfigService's requireWritable
     * for platform-wide rows.
     */
    private UUID resolveScopeOrgId(AuthPrincipal principal, String feature) {
        UUID orgId = principal.orgId();
        if (orgId == null && !principal.isSuperAdmin()) {
            throw ApiException.forbidden("Only a SUPER_ADMIN may manage the platform-wide priority list");
        }
        return orgId;
    }

    /**
     * The chosen Provider_Configuration/Model_Entry pair must belong to the
     * caller's org (or be platform-wide, usable as a fallback per Req 1.8),
     * and the Model_Entry must belong to that same Provider_Configuration.
     */
    private void validatePair(UUID orgId, PriorityPair pair) {
        if (pair == null || pair.providerConfigurationId() == null || pair.modelEntryId() == null) {
            throw ApiException.badRequest("providerConfigurationId and modelEntryId are required for each priority list entry");
        }
        ProviderConfiguration config = configurations.findById(pair.providerConfigurationId())
                .orElseThrow(() -> ApiException.badRequest("Unknown provider configuration: " + pair.providerConfigurationId()));

        boolean ownOrg = orgId != null && config.getOrgId() != null && config.getOrgId().equals(orgId);
        boolean platformWide = config.getOrgId() == null;
        if (!ownOrg && !platformWide) {
            throw ApiException.badRequest("Provider configuration does not belong to this organization");
        }

        modelEntries.findByIdAndProviderConfigurationId(pair.modelEntryId(), pair.providerConfigurationId())
                .orElseThrow(() -> ApiException.badRequest("Model entry does not belong to the given provider configuration"));
    }
}
