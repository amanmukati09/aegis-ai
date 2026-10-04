package ai.aegis.gateway.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Maps the V8 `provider_type_catalog` table — the data-driven registry of
 * supported Provider_Types (Req 11.1). Rows are seeded by the V8 migration;
 * this entity is read-mostly (admin UI/API reads it to drive the "Add
 * Provider" form and the Provider_Type dropdown).
 */
@Entity
@Table(name = "provider_type_catalog")
public class ProviderTypeCatalogEntry {

    @Id
    @Column(nullable = false, updatable = false, length = 50)
    private String id;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 50)
    private String category = "ai_provider";

    @Column(name = "credential_fields", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String credentialFields;

    @Column(name = "connection_fields", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String connectionFields;

    @Column(name = "default_models", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String defaultModels;

    @Column(name = "adapter_dependency", length = 100)
    private String adapterDependency;

    @Column(name = "adapter_class", nullable = false, length = 255)
    private String adapterClass;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ProviderTypeCatalogEntry() {
    }

    public ProviderTypeCatalogEntry(String id, String displayName, String category,
                                     String credentialFields, String connectionFields,
                                     String defaultModels, String adapterDependency,
                                     String adapterClass, boolean enabled) {
        this.id = id;
        this.displayName = displayName;
        this.category = category;
        this.credentialFields = credentialFields;
        this.connectionFields = connectionFields;
        this.defaultModels = defaultModels;
        this.adapterDependency = adapterDependency;
        this.adapterClass = adapterClass;
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCategory() {
        return category;
    }

    public String getCredentialFields() {
        return credentialFields;
    }

    public String getConnectionFields() {
        return connectionFields;
    }

    public String getDefaultModels() {
        return defaultModels;
    }

    public String getAdapterDependency() {
        return adapterDependency;
    }

    public String getAdapterClass() {
        return adapterClass;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
