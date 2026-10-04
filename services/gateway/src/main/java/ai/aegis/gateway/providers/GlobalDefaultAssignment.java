package ai.aegis.gateway.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the V8 `global_default_assignments` table — at most one row per
 * Organization (Req 5.1), the Provider_Configuration + Model_Entry used by
 * any Feature without an explicit Feature_Model_Assignment.
 */
@Entity
@Table(name = "global_default_assignments")
public class GlobalDefaultAssignment {

    @Id
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "provider_configuration_id", nullable = false)
    private UUID providerConfigurationId;

    @Column(name = "model_entry_id", nullable = false)
    private UUID modelEntryId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected GlobalDefaultAssignment() {
    }

    public GlobalDefaultAssignment(UUID orgId, UUID providerConfigurationId, UUID modelEntryId) {
        this.orgId = orgId;
        this.providerConfigurationId = providerConfigurationId;
        this.modelEntryId = modelEntryId;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getOrgId() {
        return orgId;
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
