package ai.aegis.gateway.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the V8 `feature_model_assignments` table — one row per (org, feature)
 * explicit override (Req 5.3, 5.4). Absence of a row for a (org, feature)
 * pair means "use Global_Default" (Req 5.2, 5.5), so removal of the row is
 * how an admin reverts a Feature to the Global_Default.
 */
@Entity
@Table(name = "feature_model_assignments")
public class FeatureModelAssignment {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 50)
    private String feature;

    @Column(name = "provider_configuration_id", nullable = false)
    private UUID providerConfigurationId;

    @Column(name = "model_entry_id", nullable = false)
    private UUID modelEntryId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected FeatureModelAssignment() {
    }

    public FeatureModelAssignment(UUID id, UUID orgId, String feature,
                                   UUID providerConfigurationId, UUID modelEntryId) {
        this.id = id;
        this.orgId = orgId;
        this.feature = feature;
        this.providerConfigurationId = providerConfigurationId;
        this.modelEntryId = modelEntryId;
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

    public String getFeature() {
        return feature;
    }

    public UUID getProviderConfigurationId() {
        return providerConfigurationId;
    }

    public void setProviderConfigurationId(UUID providerConfigurationId) {
        this.providerConfigurationId = providerConfigurationId;
    }

    public UUID getModelEntryId() {
        return modelEntryId;
    }

    public void setModelEntryId(UUID modelEntryId) {
        this.modelEntryId = modelEntryId;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
