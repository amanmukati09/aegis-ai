package ai.aegis.gateway.providers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the V8 migration (provider_type_catalog + supporting tables) applies
 * cleanly and that the seeded Provider_Type catalog rows and schema constraints
 * are exactly what design.md/tasks.md 1.1-1.3 require.
 *
 * This is a schema/seed-data test (not a property test) per tasks.md's note that
 * Flyway migrations themselves are integration/unit test targets, not PBT targets.
 */
@SpringBootTest
@Testcontainers
class ProviderMigrationSchemaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "a-test-secret-that-is-at-least-32-bytes-long!!");
    }

    @Autowired
    JdbcTemplate jdbc;

    private static final List<String> EXPECTED_PROVIDER_TYPES = List.of(
            "groq", "openai", "anthropic", "aws_bedrock", "azure_openai",
            "deepseek", "openrouter", "xai", "ollama"
    );

    @Test
    void catalogContainsExactlyTheNineRequiredProviderTypes() {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM provider_type_catalog ORDER BY id", String.class);
        assertThat(ids).containsExactlyInAnyOrderElementsOf(EXPECTED_PROVIDER_TYPES);
        assertThat(ids).hasSize(9);
    }

    @Test
    void groqCatalogEntryHasExpectedShape() {
        var row = jdbc.queryForMap(
                "SELECT display_name, adapter_dependency, adapter_class, enabled, " +
                        "credential_fields, default_models FROM provider_type_catalog WHERE id = 'groq'");
        assertThat(row.get("display_name")).isEqualTo("Groq");
        assertThat(row.get("adapter_dependency")).isNull();
        assertThat(row.get("adapter_class").toString())
                .isEqualTo("app.providers.groq_provider.GroqAdapter");
        assertThat((Boolean) row.get("enabled")).isTrue();
        assertThat(row.get("credential_fields").toString()).contains("api_key");
        assertThat(row.get("default_models").toString()).contains("llama-3.3-70b-versatile");
    }

    @Test
    void ollamaCatalogEntryHasExpectedShape() {
        var row = jdbc.queryForMap(
                "SELECT adapter_dependency, adapter_class, credential_fields, connection_fields " +
                        "FROM provider_type_catalog WHERE id = 'ollama'");
        assertThat(row.get("adapter_dependency")).isNull();
        assertThat(row.get("adapter_class").toString())
                .isEqualTo("app.providers.ollama_provider.OllamaAdapter");
        assertThat(row.get("credential_fields").toString()).isEqualTo("[]");
        assertThat(row.get("connection_fields").toString()).contains("base_url");
    }

    @Test
    void bedrockCatalogEntryHasMultiFieldCredentials() {
        var row = jdbc.queryForMap(
                "SELECT credential_fields, adapter_dependency, adapter_class " +
                        "FROM provider_type_catalog WHERE id = 'aws_bedrock'");
        String fields = row.get("credential_fields").toString();
        assertThat(fields).contains("access_key_id").contains("secret_access_key").contains("region");
        assertThat(row.get("adapter_dependency")).isEqualTo("boto3");
        assertThat(row.get("adapter_class").toString())
                .isEqualTo("app.providers.bedrock_adapter.BedrockAdapter");
    }

    @Test
    void everyAdapterClassFollowsTheExpectedImportPathPattern() {
        List<String> adapterClasses = jdbc.queryForList(
                "SELECT adapter_class FROM provider_type_catalog ORDER BY id", String.class);
        assertThat(adapterClasses).hasSize(9);
        for (String adapterClass : adapterClasses) {
            assertThat(adapterClass).matches("app\\.providers\\.[a-z_]+\\.[A-Za-z]+Adapter");
        }
    }

    @Test
    void providerConfigurationRejectsUnknownProviderType() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO provider_configurations " +
                        "(id, org_id, provider_type, display_name, encrypted_credentials) " +
                        "VALUES (gen_random_uuid(), NULL, 'not_a_real_provider', 'Bad Config', '{}'::jsonb)"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void modelEntryEnforcesUniqueModelIdPerProviderConfiguration() {
        UUID configId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO provider_configurations " +
                        "(id, org_id, provider_type, display_name, encrypted_credentials) " +
                        "VALUES (?, NULL, 'groq', 'Dup Test Config', '{}'::jsonb)",
                configId);
        jdbc.update(
                "INSERT INTO model_entries (id, provider_configuration_id, model_id, label) " +
                        "VALUES (gen_random_uuid(), ?, 'llama-3.3-70b-versatile', 'First')",
                configId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO model_entries (id, provider_configuration_id, model_id, label) " +
                        "VALUES (gen_random_uuid(), ?, 'llama-3.3-70b-versatile', 'Duplicate')",
                configId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void featureModelAssignmentEnforcesUniqueOrgFeaturePair() {
        UUID orgId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                orgId, "FMA Org " + orgId, "fma-org-" + orgId);

        UUID configId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO provider_configurations " +
                        "(id, org_id, provider_type, display_name, encrypted_credentials) " +
                        "VALUES (?, ?, 'groq', 'FMA Config', '{}'::jsonb)",
                configId, orgId);
        UUID modelId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO model_entries (id, provider_configuration_id, model_id, label) " +
                        "VALUES (?, ?, 'llama-3.3-70b-versatile', 'Model')",
                modelId, configId);

        jdbc.update(
                "INSERT INTO feature_model_assignments " +
                        "(id, org_id, feature, provider_configuration_id, model_entry_id) " +
                        "VALUES (gen_random_uuid(), ?, 'copilot_chat', ?, ?)",
                orgId, configId, modelId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO feature_model_assignments " +
                        "(id, org_id, feature, provider_configuration_id, model_entry_id) " +
                        "VALUES (gen_random_uuid(), ?, 'copilot_chat', ?, ?)",
                orgId, configId, modelId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void priorityListEntryEnforcesUniqueOrgFeaturePriorityOrder() {
        UUID orgId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                orgId, "Priority Org " + orgId, "priority-org-" + orgId);

        UUID configId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO provider_configurations " +
                        "(id, org_id, provider_type, display_name, encrypted_credentials) " +
                        "VALUES (?, ?, 'groq', 'Priority Config', '{}'::jsonb)",
                configId, orgId);
        UUID modelId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO model_entries (id, provider_configuration_id, model_id, label) " +
                        "VALUES (?, ?, 'llama-3.3-70b-versatile', 'Model')",
                modelId, configId);

        jdbc.update(
                "INSERT INTO priority_list_entries " +
                        "(id, org_id, feature, provider_configuration_id, model_entry_id, priority_order) " +
                        "VALUES (gen_random_uuid(), ?, NULL, ?, ?, 1)",
                orgId, configId, modelId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO priority_list_entries " +
                        "(id, org_id, feature, provider_configuration_id, model_entry_id, priority_order) " +
                        "VALUES (gen_random_uuid(), ?, NULL, ?, ?, 1)",
                orgId, configId, modelId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingOrganizationCascadesToProviderConfigurations() {
        UUID orgId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                orgId, "Cascade Org " + orgId, "cascade-org-" + orgId);
        UUID configId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO provider_configurations " +
                        "(id, org_id, provider_type, display_name, encrypted_credentials) " +
                        "VALUES (?, ?, 'groq', 'Cascade Config', '{}'::jsonb)",
                configId, orgId);

        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_configurations WHERE id = ?", Integer.class, configId);
        assertThat(count).isZero();
    }

    @Test
    void platformDefaultAssignmentAllowsOnlyOneSingletonRow() {
        UUID configId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO provider_configurations " +
                        "(id, org_id, provider_type, display_name, encrypted_credentials) " +
                        "VALUES (?, NULL, 'groq', 'Platform Default Config', '{}'::jsonb)",
                configId);
        UUID modelId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO model_entries (id, provider_configuration_id, model_id, label) " +
                        "VALUES (?, ?, 'llama-3.3-70b-versatile', 'Model')",
                modelId, configId);

        jdbc.update(
                "INSERT INTO platform_default_assignment " +
                        "(singleton, provider_configuration_id, model_entry_id) VALUES (TRUE, ?, ?)",
                configId, modelId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO platform_default_assignment " +
                        "(singleton, provider_configuration_id, model_entry_id) VALUES (TRUE, ?, ?)",
                configId, modelId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
