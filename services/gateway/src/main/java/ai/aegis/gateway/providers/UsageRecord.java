package ai.aegis.gateway.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the V8 `usage_records` table — one row per LLM call attempt, success
 * or exhausted failure (Req 6.1, 6.2). {@code inputTokens}/{@code outputTokens}
 * are left {@code null} for exhausted-failure records; token counts are never
 * fabricated for unsuccessful calls.
 */
@Entity
@Table(name = "usage_records")
public class UsageRecord {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "provider_configuration_id")
    private UUID providerConfigurationId;

    @Column(name = "model_entry_id")
    private UUID modelEntryId;

    @Column(nullable = false, length = 50)
    private String feature;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "served_pair_index")
    private Integer servedPairIndex;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected UsageRecord() {
    }

    public UsageRecord(UUID id, UUID orgId, UUID userId, UUID providerConfigurationId,
                        UUID modelEntryId, String feature, Integer inputTokens,
                        Integer outputTokens, boolean success, Integer servedPairIndex) {
        this.id = id;
        this.orgId = orgId;
        this.userId = userId;
        this.providerConfigurationId = providerConfigurationId;
        this.modelEntryId = modelEntryId;
        this.feature = feature;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.success = success;
        this.servedPairIndex = servedPairIndex;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getProviderConfigurationId() {
        return providerConfigurationId;
    }

    public UUID getModelEntryId() {
        return modelEntryId;
    }

    public String getFeature() {
        return feature;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public boolean isSuccess() {
        return success;
    }

    public Integer getServedPairIndex() {
        return servedPairIndex;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
