package ai.aegis.gateway.providers;

import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.user.Role;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link UsageRecordService}'s success/failure recording against a
 * real Postgres instance (Testcontainers), matching the pattern established
 * by {@link ProviderMigrationSchemaTest}.
 */
@JqwikSpringSupport
@SpringBootTest
class UsageRecordServiceTest {

    // Started eagerly in a static initializer (rather than relying on the
    // @Testcontainers/@Container JUnit5 Jupiter extension lifecycle) because
    // jqwik is a separate JUnit Platform engine from Jupiter: the ordering
    // between jqwik's engine startup and the Testcontainers Jupiter
    // extension's beforeAll callback is not guaranteed, which produced
    // "Mapped port can only be obtained after the container is started"
    // under @JqwikSpringSupport. Starting the container unconditionally
    // before any test engine runs sidesteps that race entirely.
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("jwt.secret", () -> "a-test-secret-that-is-at-least-32-bytes-long!!");
    }

    @Autowired
    UsageRecordService usageRecordService;

    @Autowired
    UsageRecordRepository usageRecordRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ProviderConfigService providerConfigService;

    /**
     * Inserts a fresh organization row and returns its id. usage_records.org_id
     * is a real FK to organizations(id) (ON DELETE SET NULL), so property
     * methods must not use a bare UUID.randomUUID() for orgId.
     */
    private UUID createOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                orgId, "Org " + orgId, "org-" + orgId);
        return orgId;
    }

    /**
     * Inserts a fresh user row (scoped to the given org) and returns its id.
     * usage_records.user_id is a real FK to users(id) (ON DELETE SET NULL).
     */
    private UUID createUser(UUID orgId) {
        UUID userId = UUID.randomUUID();
        String email = "user+" + userId + "@org.test";
        jdbc.update(
                "INSERT INTO users (id, org_id, email, full_name, role) VALUES (?, ?, ?, ?, ?)",
                userId, orgId, email, "Usage Test User", "member");
        return userId;
    }

    /**
     * Creates a real Provider_Configuration (+ its default Model_Entry) for
     * the given org via {@link ProviderConfigService}, since
     * usage_records.provider_configuration_id / model_entry_id are real FKs
     * (ON DELETE SET NULL) to provider_configurations / model_entries.
     */
    private ModelAndConfig createProviderConfigAndModel(UUID orgId, UUID creatorUserId) {
        AuthPrincipal principal = new AuthPrincipal(creatorUserId, orgId, "creator+" + creatorUserId + "@org.test", Role.ORG_ADMIN);
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("api_key", "gsk_" + UUID.randomUUID());
        ProviderConfigService.ProviderConfigView created = providerConfigService.create(
                principal, "groq", "Usage Fixture " + UUID.randomUUID(), credentials, Map.of());
        UUID modelEntryId = created.modelEntries().get(0).id();
        return new ModelAndConfig(created.id(), modelEntryId);
    }

    record ModelAndConfig(UUID providerConfigurationId, UUID modelEntryId) {
    }

    /**
     * Feature: ai-provider-flexibility, Property 17: Successful call produces an accurate Usage_Record
     * Validates: Requirements 6.1
     */
    @Property(tries = 50)
    @Tag("ai-provider-flexibility")
    @Tag("property-17")
    void successfulCallProducesAccurateUsageRecord(@ForAll @StringLength(min = 3, max = 20) @AlphaChars @NotBlank String feature,
                                                     @ForAll @IntRange(min = 0, max = 1_000_000) int inputTokens,
                                                     @ForAll @IntRange(min = 0, max = 1_000_000) int outputTokens,
                                                     @ForAll @IntRange(min = 0, max = 10) int servedPairIndex) {
        UUID orgId = createOrg();
        UUID userId = createUser(orgId);
        ModelAndConfig fixture = createProviderConfigAndModel(orgId, userId);
        UUID providerConfigurationId = fixture.providerConfigurationId();
        UUID modelEntryId = fixture.modelEntryId();

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);

        UsageRecord saved = usageRecordService.recordSuccess(
                orgId, userId, providerConfigurationId, modelEntryId, feature,
                inputTokens, outputTokens, servedPairIndex);

        OffsetDateTime after = OffsetDateTime.now().plusSeconds(5);

        Optional<UsageRecord> reloaded = usageRecordRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        UsageRecord record = reloaded.get();

        assertThat(record.isSuccess()).isTrue();
        assertThat(record.getOrgId()).isEqualTo(orgId);
        assertThat(record.getUserId()).isEqualTo(userId);
        assertThat(record.getProviderConfigurationId()).isEqualTo(providerConfigurationId);
        assertThat(record.getModelEntryId()).isEqualTo(modelEntryId);
        assertThat(record.getFeature()).isEqualTo(feature);
        assertThat(record.getInputTokens()).isEqualTo(inputTokens);
        assertThat(record.getOutputTokens()).isEqualTo(outputTokens);
        assertThat(record.getServedPairIndex()).isEqualTo(servedPairIndex);
        assertThat(record.getCreatedAt()).isBetween(before, after);
    }

    /**
     * Feature: ai-provider-flexibility, Property 18: Exhausted-failure Usage_Record never fabricates tokens
     * Validates: Requirements 6.2
     */
    @Property(tries = 50)
    @Tag("ai-provider-flexibility")
    @Tag("property-18")
    void exhaustedFailureUsageRecordNeverFabricatesTokens(@ForAll @StringLength(min = 3, max = 20) @AlphaChars @NotBlank String feature) {
        UUID orgId = createOrg();
        UUID userId = createUser(orgId);

        UsageRecord saved = usageRecordService.recordExhaustedFailure(orgId, userId, feature);

        Optional<UsageRecord> reloaded = usageRecordRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        UsageRecord record = reloaded.get();

        assertThat(record.isSuccess()).isFalse();
        assertThat(record.getOrgId()).isEqualTo(orgId);
        assertThat(record.getUserId()).isEqualTo(userId);
        assertThat(record.getFeature()).isEqualTo(feature);
        assertThat(record.getInputTokens()).isNull();
        assertThat(record.getOutputTokens()).isNull();
        assertThat(record.getProviderConfigurationId()).isNull();
        assertThat(record.getModelEntryId()).isNull();
        assertThat(record.getServedPairIndex()).isNull();
    }
}
