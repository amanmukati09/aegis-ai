package ai.aegis.gateway.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the V8 `platform_default_assignment` table — a true singleton row
 * (PK is the always-TRUE {@code singleton} boolean column, enforced by a
 * {@code CHECK (singleton)} constraint in the migration) holding the
 * platform-wide Global_Default fallback (Req 1.8, 5.6).
 */
@Entity
@Table(name = "platform_default_assignment")
public class PlatformDefaultAssignment {

    @Id
    @Column(nullable = false, updatable = false)
    private Boolean singleton = Boolean.TRUE;

    @Column(name = "provider_configuration_id", nullable = false)
    private UUID providerConfigurationId;

    @Column(name = "model_entry_id", nullable = false)
    private UUID modelEntryId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected PlatformDefaultAssignment() {
    }

    public PlatformDefaultAssignment(UUID providerConfigurationId, UUID modelEntryId) {
        this.singleton = Boolean.TRUE;
        this.providerConfigurationId = providerConfigurationId;
        this.modelEntryId = modelEntryId;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public Boolean getSingleton() {
        return singleton;
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
