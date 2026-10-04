package ai.aegis.gateway.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the V8 `provider_configurations` table. {@code orgId == null} means a
 * platform-wide fallback configuration (Req 1.8), usable by any Organization
 * without its own configuration for that Provider_Type.
 */
@Entity
@Table(name = "provider_configurations")
public class ProviderConfiguration {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "provider_type", nullable = false, length = 50)
    private String providerType;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "encrypted_credentials", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String encryptedCredentials;

    @Column(name = "credential_hints", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String credentialHints = "{}";

    @Column(name = "connection_settings", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String connectionSettings = "{}";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected ProviderConfiguration() {
    }

    public ProviderConfiguration(UUID id, UUID orgId, String providerType, String displayName,
                                  String encryptedCredentials, String credentialHints,
                                  String connectionSettings, UUID createdBy) {
        this.id = id;
        this.orgId = orgId;
        this.providerType = providerType;
        this.displayName = displayName;
        this.encryptedCredentials = encryptedCredentials;
        this.credentialHints = credentialHints;
        this.connectionSettings = connectionSettings;
        this.createdBy = createdBy;
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

    public String getProviderType() {
        return providerType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEncryptedCredentials() {
        return encryptedCredentials;
    }

    public void setEncryptedCredentials(String encryptedCredentials) {
        this.encryptedCredentials = encryptedCredentials;
    }

    public String getCredentialHints() {
        return credentialHints;
    }

    public void setCredentialHints(String credentialHints) {
        this.credentialHints = credentialHints;
    }

    public String getConnectionSettings() {
        return connectionSettings;
    }

    public void setConnectionSettings(String connectionSettings) {
        this.connectionSettings = connectionSettings;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
