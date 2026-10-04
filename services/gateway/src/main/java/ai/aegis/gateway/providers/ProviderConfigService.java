package ai.aegis.gateway.providers;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for Provider_Configuration + its pre-populated Model_Entry rows (Req
 * 1.1-1.3, 1.5-1.7, 3.3). Credential values are encrypted independently per
 * field via {@link CredentialCipher} and never decrypted here: masking is
 * always derived from {@code credential_hints}, computed once at write time
 * from the plaintext the caller supplied in that same request. Decryption
 * for actual provider calls happens only in ML_Service (see design.md).
 */
@Service
public class ProviderConfigService {

    private static final int HINT_LENGTH = 4;

    private final ProviderConfigurationRepository configurations;
    private final ModelEntryRepository modelEntries;
    private final ProviderTypeCatalogRepository catalog;
    private final CredentialCipher cipher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ProviderConfigService(ProviderConfigurationRepository configurations,
                                  ModelEntryRepository modelEntries,
                                  ProviderTypeCatalogRepository catalog,
                                  CredentialCipher cipher,
                                  AuditService auditService,
                                  ObjectMapper objectMapper) {
        this.configurations = configurations;
        this.modelEntries = modelEntries;
        this.catalog = catalog;
        this.cipher = cipher;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------------

    public record ModelEntryView(UUID id, String modelId, String label) {
        static ModelEntryView of(ModelEntry m) {
            return new ModelEntryView(m.getId(), m.getModelId(), m.getLabel());
        }
    }

    public record ProviderConfigView(UUID id, UUID orgId, String providerType, String displayName,
                                      Map<String, String> credentialHints, boolean enabled,
                                      boolean isPlatformWide, List<ModelEntryView> modelEntries,
                                      OffsetDateTime createdAt) {
    }

    public record CatalogField(String key, String label, boolean secret, boolean required) {
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    @Transactional
    public ProviderConfigView create(AuthPrincipal principal, String providerType, String displayName,
                                      Map<String, String> credentials, Map<String, String> connectionSettings) {
        ProviderTypeCatalogEntry catalogEntry = catalog.findById(providerType)
                .orElseThrow(() -> ApiException.badRequest("Unknown provider type: " + providerType));
        if (!catalogEntry.isEnabled()) {
            throw ApiException.badRequest("Provider type is not enabled: " + providerType);
        }

        validateCredentialFields(catalogEntry, credentials);

        // Platform-wide (org-null) creation is SUPER_ADMIN only; a regular ORG_ADMIN
        // always creates a configuration scoped to their own org (Req 1.7, design.md).
        UUID orgId = principal.orgId();
        if (orgId == null && !principal.isSuperAdmin()) {
            throw ApiException.forbidden("Only a SUPER_ADMIN may create a platform-wide provider configuration");
        }

        Map<String, String> safeCredentials = credentials == null ? Map.of() : credentials;
        Map<String, String> encryptedCredentials = new LinkedHashMap<>();
        Map<String, String> credentialHints = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : safeCredentials.entrySet()) {
            encryptedCredentials.put(entry.getKey(), cipher.encrypt(entry.getValue()));
            credentialHints.put(entry.getKey(), hint(entry.getValue()));
        }

        ProviderConfiguration config = new ProviderConfiguration(
                UUID.randomUUID(),
                orgId,
                providerType,
                displayName,
                writeJson(encryptedCredentials),
                writeJson(credentialHints),
                writeJson(connectionSettings == null ? Map.of() : connectionSettings),
                principal.userId());
        configurations.save(config);

        List<ModelEntry> defaultModels = createDefaultModelEntries(catalogEntry, config.getId());

        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "provider_configuration_created", "provider_configuration", config.getId().toString(), null);

        return toView(config, defaultModels);
    }

    private List<ModelEntry> createDefaultModelEntries(ProviderTypeCatalogEntry catalogEntry, UUID configId) {
        List<Map<String, String>> defaults = readJsonList(catalogEntry.getDefaultModels());
        List<ModelEntry> created = new java.util.ArrayList<>();
        for (Map<String, String> model : defaults) {
            String modelId = model.get("id");
            if (modelId == null || modelId.isBlank()) {
                continue;
            }
            ModelEntry entry = new ModelEntry(UUID.randomUUID(), configId, modelId, model.get("label"));
            modelEntries.save(entry);
            created.add(entry);
        }
        return created;
    }

    private void validateCredentialFields(ProviderTypeCatalogEntry catalogEntry, Map<String, String> credentials) {
        List<Map<String, Object>> declaredFields = readJsonMapList(catalogEntry.getCredentialFields());
        Map<String, String> safeCredentials = credentials == null ? Map.of() : credentials;
        for (Map<String, Object> field : declaredFields) {
            String key = String.valueOf(field.get("key"));
            String value = safeCredentials.get(key);
            if (value == null || value.isBlank()) {
                throw ApiException.badRequest("Missing required credential field: " + key);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------

    @Transactional
    public ProviderConfigView update(AuthPrincipal principal, UUID id, String displayName,
                                      Map<String, String> credentials, Map<String, String> connectionSettings,
                                      Boolean enabled) {
        ProviderConfiguration config = requireVisible(principal, id);
        requireWritable(principal, config);

        if (displayName != null) {
            config.setDisplayName(displayName);
        }

        if (credentials != null && !credentials.isEmpty()) {
            Map<String, String> encryptedCredentials = new LinkedHashMap<>(readJsonMap(config.getEncryptedCredentials()));
            Map<String, String> credentialHints = new LinkedHashMap<>(readJsonMap(config.getCredentialHints()));
            for (Map.Entry<String, String> entry : credentials.entrySet()) {
                encryptedCredentials.put(entry.getKey(), cipher.encrypt(entry.getValue()));
                credentialHints.put(entry.getKey(), hint(entry.getValue()));
            }
            config.setEncryptedCredentials(writeJson(encryptedCredentials));
            config.setCredentialHints(writeJson(credentialHints));
        }

        if (connectionSettings != null && !connectionSettings.isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(readJsonMap(config.getConnectionSettings()));
            merged.putAll(connectionSettings);
            config.setConnectionSettings(writeJson(merged));
        }

        if (enabled != null) {
            config.setEnabled(enabled);
        }

        configurations.save(config);

        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "provider_configuration_updated", "provider_configuration", config.getId().toString(), null);

        return toView(config, modelEntries.findByProviderConfigurationId(config.getId()));
    }

    // ---------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------

    @Transactional
    public void delete(AuthPrincipal principal, UUID id) {
        ProviderConfiguration config = requireVisible(principal, id);
        requireWritable(principal, config);

        // model_entries (and any other FK-dependent rows) cascade via ON DELETE
        // CASCADE from the V8 migration -- no manual child cleanup needed.
        configurations.delete(config);

        auditService.record(principal.orgId(), principal.userId(), principal.email(),
                "provider_configuration_deleted", "provider_configuration", id.toString(), null);
    }

    // ---------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ProviderConfigView> list(AuthPrincipal principal) {
        return configurations.findByOrgIdOrOrgIdIsNull(principal.orgId()).stream()
                .map(c -> toView(c, modelEntries.findByProviderConfigurationId(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProviderConfigView get(AuthPrincipal principal, UUID id) {
        ProviderConfiguration config = requireVisible(principal, id);
        return toView(config, modelEntries.findByProviderConfigurationId(config.getId()));
    }

    // ---------------------------------------------------------------------
    // Authorization / visibility helpers
    // ---------------------------------------------------------------------

    /**
     * A configuration is visible if it belongs to the caller's org, or is
     * platform-wide (org_id null) -- platform-wide rows act as a fallback
     * visible to every org (Req 1.8).
     */
    private ProviderConfiguration requireVisible(AuthPrincipal principal, UUID id) {
        ProviderConfiguration config = configurations.findById(id)
                .orElseThrow(() -> ApiException.notFound("Provider configuration not found"));
        boolean ownOrg = config.getOrgId() != null && config.getOrgId().equals(principal.orgId());
        boolean platformWide = config.getOrgId() == null;
        if (!ownOrg && !platformWide) {
            throw ApiException.notFound("Provider configuration not found");
        }
        return config;
    }

    /**
     * Write access (update/delete): own-org configurations may be modified by
     * that org's SUPER_ADMIN/ORG_ADMIN; platform-wide (org_id null) rows may
     * only be modified by a SUPER_ADMIN.
     */
    private void requireWritable(AuthPrincipal principal, ProviderConfiguration config) {
        if (config.getOrgId() == null) {
            if (!principal.isSuperAdmin()) {
                throw ApiException.forbidden("Only a SUPER_ADMIN may modify a platform-wide provider configuration");
            }
            return;
        }
        if (!config.getOrgId().equals(principal.orgId())) {
            throw ApiException.forbidden("Cannot modify another organization's provider configuration");
        }
    }

    // ---------------------------------------------------------------------
    // JSON / masking helpers
    // ---------------------------------------------------------------------

    private ProviderConfigView toView(ProviderConfiguration config, List<ModelEntry> models) {
        Map<String, String> hints = readJsonMap(config.getCredentialHints());
        List<ModelEntryView> modelViews = models.stream().map(ModelEntryView::of).toList();
        return new ProviderConfigView(
                config.getId(),
                config.getOrgId(),
                config.getProviderType(),
                config.getDisplayName(),
                hints,
                config.isEnabled(),
                config.getOrgId() == null,
                modelViews,
                config.getCreatedAt());
    }

    private String hint(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        int from = Math.max(0, plaintext.length() - HINT_LENGTH);
        return "..." + plaintext.substring(from);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize provider configuration JSON", e);
        }
    }

    private Map<String, String> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse provider configuration JSON", e);
        }
    }

    private List<Map<String, String>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse provider configuration JSON", e);
        }
    }

    private List<Map<String, Object>> readJsonMapList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse provider configuration JSON", e);
        }
    }
}
