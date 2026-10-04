package ai.aegis.gateway.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the V8 `priority_list_entries` table — ordered failover candidates
 * (Req 4.1, 4.2), scoped globally ({@code feature == null}) or per-feature,
 * per Organization or platform-wide ({@code orgId == null}).
 */
@Entity
@Table(name = "priority_list_entries")
public class PriorityListEntry {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(length = 50)
    private String feature;

    @Column(name = "provider_configuration_id", nullable = false)
    private UUID providerConfigurationId;

    @Column(name = "model_entry_id", nullable = false)
    private UUID modelEntryId;

    @Column(name = "priority_order", nullable = false)
    private int priorityOrder;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected PriorityListEntry() {
    }

    public PriorityListEntry(UUID id, UUID orgId, String feature, UUID providerConfigurationId,
                              UUID modelEntryId, int priorityOrder) {
        this.id = id;
        this.orgId = orgId;
        this.feature = feature;
        this.providerConfigurationId = providerConfigurationId;
        this.modelEntryId = modelEntryId;
        this.priorityOrder = priorityOrder;
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

    public UUID getModelEntryId() {
        return modelEntryId;
    }

    public int getPriorityOrder() {
        return priorityOrder;
    }

    public void setPriorityOrder(int priorityOrder) {
        this.priorityOrder = priorityOrder;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
