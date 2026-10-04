package ai.aegis.gateway.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the V8 `model_entries` table — a named model identifier under a
 * Provider_Configuration (Req 3.1-3.3).
 */
@Entity
@Table(name = "model_entries")
public class ModelEntry {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider_configuration_id", nullable = false)
    private UUID providerConfigurationId;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    private String label;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ModelEntry() {
    }

    public ModelEntry(UUID id, UUID providerConfigurationId, String modelId, String label) {
        this.id = id;
        this.providerConfigurationId = providerConfigurationId;
        this.modelId = modelId;
        this.label = label;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderConfigurationId() {
        return providerConfigurationId;
    }

    public String getModelId() {
        return modelId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
