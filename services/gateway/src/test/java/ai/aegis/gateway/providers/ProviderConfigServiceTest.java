package ai.aegis.gateway.providers;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.user.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.spring.JqwikSpringSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property and example tests for {@link ProviderConfigService} (tasks.md 3.3-3.11).
 *
 * Uses the real seeded V8 catalog rows ('groq': single credential field
 * api_key; 'aws_bedrock': multi-field access_key_id/secret_access_key/region)
 * as generator fixtures, following the Testcontainers + jqwik conventions
 * established in {@code ProviderMigrationSchemaTest} and {@code CredentialCipherTest}.
 */
@JqwikSpringSupport
@SpringBootTest
class ProviderConfigServiceTest {

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
    ProviderConfigService service;

    @Autowired
    ProviderConfigurationRepository configurationRepository;

    @Autowired
    ModelEntryRepository modelEntryRepository;

    @Autowired
    JdbcTemplate jdbc;

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUpOrgs() {
        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                orgA, "Org A " + orgA, "org-a-" + orgA);
        jdbc.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                orgB, "Org B " + orgB, "org-b-" + orgB);
    }

    // ---------------------------------------------------------------------
    // Fixtures / helpers
    // ---------------------------------------------------------------------

    /**
     * Inserts a fresh organization row and returns its id. Kept as a helper
     * (rather than switching {@code @Property} methods over to the
     * {@code orgA}/{@code orgB} instance fields) so each generated sample
     * still gets its own freshly isolated organization, independent of
     * {@code @BeforeEach} timing.
     */
    private UUID createOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                orgId, "Org " + orgId, "org-" + orgId);
        return orgId;
    }

    private AuthPrincipal orgAdmin(UUID orgId) {
        UUID userId = UUID.randomUUID();
        String email = "admin+" + userId + "@org.test";
        jdbc.update(
                "INSERT INTO users (id, org_id, email, full_name, role) VALUES (?, ?, ?, ?, ?)",
                userId, orgId, email, "Org Admin", "org_admin");
        return new AuthPrincipal(userId, orgId, email, Role.ORG_ADMIN);
    }

    private AuthPrincipal superAdmin(UUID orgId) {
        UUID userId = UUID.randomUUID();
        String email = "super+" + userId + "@org.test";
        jdbc.update(
                "INSERT INTO users (id, org_id, email, full_name, role) VALUES (?, ?, ?, ?, ?)",
                userId, orgId, email, "Super Admin", "super_admin");
        return new AuthPrincipal(userId, orgId, email, Role.SUPER_ADMIN);
    }

    private AuthPrincipal member(UUID orgId) {
        UUID userId = UUID.randomUUID();
        String email = "member+" + userId + "@org.test";
        jdbc.update(
                "INSERT INTO users (id, org_id, email, full_name, role) VALUES (?, ?, ?, ?, ?)",
                userId, orgId, email, "Member", "member");
        return new AuthPrincipal(userId, orgId, email, Role.MEMBER);
    }

    private static Map<String, String> groqCredentials(String apiKey) {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("api_key", apiKey);
        return creds;
    }

    private static Map<String, String> bedrockCredentials(String accessKey, String secretKey, String region) {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("access_key_id", accessKey);
        creds.put("secret_access_key", secretKey);
        creds.put("region", region);
        return creds;
    }

    /** Generator: a valid credential payload for either 'groq' or 'aws_bedrock', paired with the provider type id. */
    @Provide
    Arbitrary<CatalogPayload> catalogPayloads() {
        Arbitrary<String> secretPart = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(40);
        Arbitrary<CatalogPayload> groq = secretPart.map(key ->
                new CatalogPayload("groq", groqCredentials("gsk_" + key)));
        Arbitrary<CatalogPayload> bedrock = Combinators.combine(secretPart, secretPart, secretPart)
                .as((ak, sk, region) -> new CatalogPayload("aws_bedrock",
                        bedrockCredentials("AKIA" + ak, sk, "us-east-" + (1 + Math.abs(region.hashCode() % 9)))));
        return Arbitraries.oneOf(groq, bedrock);
    }

    record CatalogPayload(String providerType, Map<String, String> credentials) {
    }

    // ---------------------------------------------------------------------
    // 3.3 Property 1: Provider_Configuration create round-trip
    // Validates: Requirements 1.1, 3.3
    // ---------------------------------------------------------------------

    /**
     * Feature: ai-provider-flexibility, Property 1: Provider_Configuration create round-trip
     * Validates: Requirements 1.1, 3.3
     */
    @Property(tries = 100)
    @Tag("ai-provider-flexibility")
    @Tag("property-1")
    void createRoundTripPreservesFieldsAndPopulatesDefaultModels(@ForAll("catalogPayloads") CatalogPayload payload) {
        AuthPrincipal principal = orgAdmin(createOrg());
        Map<String, String> connectionSettings = Map.of("base_url", "https://example.test");

        ProviderConfigService.ProviderConfigView created = service.create(
                principal, payload.providerType(), "My " + payload.providerType(), payload.credentials(), connectionSettings);

        assertThat(created.providerType()).isEqualTo(payload.providerType());
        assertThat(created.displayName()).isEqualTo("My " + payload.providerType());
        // Both groq and aws_bedrock have non-empty default_models in the V8 seed.
        assertThat(created.modelEntries()).isNotEmpty();

        ProviderConfigService.ProviderConfigView fetched = service.get(principal, created.id());
        assertThat(fetched.providerType()).isEqualTo(payload.providerType());
        assertThat(fetched.displayName()).isEqualTo(created.displayName());
        assertThat(fetched.modelEntries()).hasSameSizeAs(created.modelEntries());

        List<ProviderConfigService.ProviderConfigView> listed = service.list(principal);
        assertThat(listed).anySatisfy(v -> assertThat(v.id()).isEqualTo(created.id()));
    }

    // ---------------------------------------------------------------------
    // 3.4 Property 2: Provider_Configuration update round-trip
    // Validates: Requirements 1.2
    // ---------------------------------------------------------------------

    @Provide
    Arbitrary<PartialUpdate> partialUpdates() {
        Arbitrary<String> displayName = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20)
                .map(s -> "Updated " + s);
        Arbitrary<String> apiKey = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(30)
                .map(s -> "gsk_new_" + s);
        Arbitrary<Boolean> enabled = Arbitraries.of(true, false);
        Arbitrary<String> baseUrl = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .map(s -> "https://" + s + ".example.test");

        return Combinators.combine(
                Arbitraries.of(true, false), displayName,
                Arbitraries.of(true, false), apiKey,
                Arbitraries.of(true, false), baseUrl,
                Arbitraries.of(true, false), enabled
        ).as((touchName, name, touchCreds, key, touchSettings, url, touchEnabled, enabledVal) -> new PartialUpdate(
                touchName ? name : null,
                touchCreds ? groqCredentials(key) : null,
                touchSettings ? Map.of("base_url", url) : null,
                touchEnabled ? enabledVal : null));
    }

    record PartialUpdate(String displayName, Map<String, String> credentials,
                          Map<String, String> connectionSettings, Boolean enabled) {
        boolean touchesNothing() {
            return displayName == null && credentials == null && connectionSettings == null && enabled == null;
        }
    }

    /**
     * Feature: ai-provider-flexibility, Property 2: Provider_Configuration update round-trip
     * Validates: Requirements 1.2
     */
    @Property(tries = 100)
    @Tag("ai-provider-flexibility")
    @Tag("property-2")
    void updateRoundTripReflectsChangedFieldsAndPreservesUnchangedOnes(@ForAll("partialUpdates") PartialUpdate update) {
        AuthPrincipal principal = orgAdmin(createOrg());
        ProviderConfigService.ProviderConfigView original = service.create(
                principal, "groq", "Original Display Name", groqCredentials("gsk_original_key_value"),
                Map.of("base_url", "https://original.example.test"));
        Map<String, String> originalHints = original.credentialHints();
        boolean originalEnabled = original.enabled();

        ProviderConfigService.ProviderConfigView updated = service.update(
                principal, original.id(), update.displayName(), update.credentials(),
                update.connectionSettings(), update.enabled());

        if (update.displayName() != null) {
            assertThat(updated.displayName()).isEqualTo(update.displayName());
        } else {
            assertThat(updated.displayName()).isEqualTo(original.displayName());
        }

        if (update.credentials() != null) {
            assertThat(updated.credentialHints().get("api_key")).endsWith(
                    update.credentials().get("api_key").substring(update.credentials().get("api_key").length() - 4));
            assertThat(updated.credentialHints().get("api_key")).isNotEqualTo(originalHints.get("api_key"));
        } else {
            assertThat(updated.credentialHints()).isEqualTo(originalHints);
        }

        if (update.enabled() != null) {
            assertThat(updated.enabled()).isEqualTo(update.enabled());
        } else {
            assertThat(updated.enabled()).isEqualTo(originalEnabled);
        }

        ProviderConfigService.ProviderConfigView refetched = service.get(principal, original.id());
        assertThat(refetched.displayName()).isEqualTo(updated.displayName());
        assertThat(refetched.enabled()).isEqualTo(updated.enabled());
    }

    // ---------------------------------------------------------------------
    // 3.5 Property 3: Deleted Provider_Configuration is no longer resolvable
    // Validates: Requirements 1.3
    // ---------------------------------------------------------------------

    /**
     * Feature: ai-provider-flexibility, Property 3: Deleted Provider_Configuration is no longer resolvable
     * Validates: Requirements 1.3
     */
    @Test
    void deletedConfigurationIsNoLongerListedOrGettable() {
        AuthPrincipal principal = orgAdmin(orgA);
        ProviderConfigService.ProviderConfigView created = service.create(
                principal, "groq", "To Delete", groqCredentials("gsk_delete_me"), Map.of());

        service.delete(principal, created.id());

        List<ProviderConfigService.ProviderConfigView> listed = service.list(principal);
        assertThat(listed).noneMatch(v -> v.id().equals(created.id()));

        assertThatThrownBy(() -> service.get(principal, created.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }

    // ---------------------------------------------------------------------
    // 3.6 Property 5: Credentials are always masked in API responses
    // Validates: Requirements 1.6, 7.4
    // ---------------------------------------------------------------------

    /**
     * Feature: ai-provider-flexibility, Property 5: Credentials are always masked in API responses
     * Validates: Requirements 1.6, 7.4
     */
    @Property(tries = 100)
    @Tag("ai-provider-flexibility")
    @Tag("property-5")
    void credentialHintsNeverExposeMoreThanLastFourPlaintextChars(
            @ForAll @net.jqwik.api.constraints.StringLength(min = 5, max = 40) String rawSecret) {
        // Constrain to alnum so substring/containment checks aren't confused by
        // incidental overlaps from punctuation/whitespace noise.
        String secret = "sk_" + rawSecret.replaceAll("[^a-zA-Z0-9]", "a");
        AuthPrincipal principal = orgAdmin(createOrg());

        ProviderConfigService.ProviderConfigView created = service.create(
                principal, "groq", "Masking Test", groqCredentials(secret), Map.of());
        ProviderConfigService.ProviderConfigView fetched = service.get(principal, created.id());
        ProviderConfigService.ProviderConfigView listed = service.list(principal).stream()
                .filter(v -> v.id().equals(created.id())).findFirst().orElseThrow();

        for (ProviderConfigService.ProviderConfigView view : List.of(created, fetched, listed)) {
            String hint = view.credentialHints().get("api_key");
            assertThat(hint).isNotNull();
            assertThat(hint).isNotEqualTo(secret);
            String last4 = secret.substring(secret.length() - 4);
            // The hint must not contain any longer suffix of the plaintext than the last 4 chars.
            if (secret.length() > 4) {
                String last5 = secret.substring(secret.length() - 5);
                assertThat(hint).doesNotContain(last5);
            }
            assertThat(hint).endsWith(last4);
        }
    }

    // ---------------------------------------------------------------------
    // 3.7 Property 24: Deleting a configuration removes its credential material
    // Validates: Requirements 7.5
    // ---------------------------------------------------------------------

    /**
     * Feature: ai-provider-flexibility, Property 24: Deleting a configuration removes its credential material
     * Validates: Requirements 7.5
     */
    @Test
    void deletingConfigurationRemovesRowAndCredentialMaterialFromRepository() {
        AuthPrincipal principal = orgAdmin(orgA);
        ProviderConfigService.ProviderConfigView created = service.create(
                principal, "groq", "Credential Removal Test", groqCredentials("gsk_sensitive_value"), Map.of());

        assertThat(configurationRepository.findById(created.id())).isPresent();

        service.delete(principal, created.id());

        assertThat(configurationRepository.findById(created.id())).isEmpty();
    }

    // ---------------------------------------------------------------------
    // 3.8 Property 9: Model_Entry add/remove round-trip
    // (exercised against default-model pre-population + cascade delete; see
    // report for the gap note on explicit single-model add/remove.)
    // Validates: Requirements 3.1, 3.2
    // ---------------------------------------------------------------------

    /**
     * Feature: ai-provider-flexibility, Property 9: Model_Entry add/remove round-trip
     * Validates: Requirements 3.1, 3.2
     */
    @Test
    void defaultModelEntriesArePopulatedAndCascadeDeletedWithConfiguration() {
        AuthPrincipal principal = orgAdmin(orgA);

        ProviderConfigService.ProviderConfigView groqConfig = service.create(
                principal, "groq", "Groq Models Test", groqCredentials("gsk_models_test"), Map.of());
        List<ModelEntry> groqModels = modelEntryRepository.findByProviderConfigurationId(groqConfig.id());
        assertThat(groqModels).hasSameSizeAs(groqConfig.modelEntries());
        assertThat(groqModels).isNotEmpty();

        ProviderConfigService.ProviderConfigView bedrockConfig = service.create(
                principal, "aws_bedrock", "Bedrock Models Test",
                bedrockCredentials("AKIAEXAMPLE", "secretvalue", "us-east-1"), Map.of());
        List<ModelEntry> bedrockModels = modelEntryRepository.findByProviderConfigurationId(bedrockConfig.id());
        assertThat(bedrockModels).hasSameSizeAs(bedrockConfig.modelEntries());
        assertThat(bedrockModels).isNotEmpty();

        service.delete(principal, groqConfig.id());
        assertThat(modelEntryRepository.findByProviderConfigurationId(groqConfig.id())).isEmpty();

        service.delete(principal, bedrockConfig.id());
        assertThat(modelEntryRepository.findByProviderConfigurationId(bedrockConfig.id())).isEmpty();
    }

    // ---------------------------------------------------------------------
    // 3.9 Property 6: Provider_Configuration organization scoping invariant
    // Validates: Requirements 1.7
    // ---------------------------------------------------------------------

    /**
     * Feature: ai-provider-flexibility, Property 6: Provider_Configuration organization scoping invariant
     * Validates: Requirements 1.7
     */
    @Test
    void createdConfigurationOrgIdIsNullOrMatchesCreatingPrincipalsOrg() {
        AuthPrincipal admin = orgAdmin(orgA);
        ProviderConfigService.ProviderConfigView ownOrgConfig = service.create(
                admin, "groq", "Own Org Config", groqCredentials("gsk_scope_test"), Map.of());
        assertThat(ownOrgConfig.orgId()).isEqualTo(orgA);

        AuthPrincipal superAdminPlatform = superAdmin(null);
        ProviderConfigService.ProviderConfigView platformConfig = service.create(
                superAdminPlatform, "groq", "Platform Config", groqCredentials("gsk_platform_test"), Map.of());
        assertThat(platformConfig.orgId()).isNull();
        assertThat(platformConfig.isPlatformWide()).isTrue();
    }

    /**
     * Feature: ai-provider-flexibility, Property 6: Provider_Configuration organization scoping invariant
     * Validates: Requirements 1.7
     */
    @Test
    void nonSuperAdminWithNullOrgCannotCreatePlatformWideConfiguration() {
        AuthPrincipal orgAdminWithNoOrg = new AuthPrincipal(UUID.randomUUID(), null, "noorg@org.test", Role.ORG_ADMIN);

        assertThatThrownBy(() -> service.create(
                orgAdminWithNoOrg, "groq", "Should Fail", groqCredentials("gsk_should_fail"), Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(403));
    }

    // ---------------------------------------------------------------------
    // 3.10 Property 7: Platform-wide fallback visibility
    // Validates: Requirements 1.8
    // ---------------------------------------------------------------------

    /**
     * Feature: ai-provider-flexibility, Property 7: Platform-wide fallback visibility
     * Validates: Requirements 1.8
     */
    @Test
    void platformWideConfigurationIsVisibleToOtherOrgsListAndGet() {
        AuthPrincipal superAdminPlatform = superAdmin(null);
        ProviderConfigService.ProviderConfigView platformConfig = service.create(
                superAdminPlatform, "groq", "Platform Fallback", groqCredentials("gsk_fallback_test"), Map.of());

        // orgB has no Provider_Configuration of its own for 'groq'.
        AuthPrincipal orgBAdmin = orgAdmin(orgB);

        List<ProviderConfigService.ProviderConfigView> listedByOrgB = service.list(orgBAdmin);
        assertThat(listedByOrgB).anySatisfy(v -> assertThat(v.id()).isEqualTo(platformConfig.id()));

        ProviderConfigService.ProviderConfigView fetchedByOrgB = service.get(orgBAdmin, platformConfig.id());
        assertThat(fetchedByOrgB.id()).isEqualTo(platformConfig.id());
        assertThat(fetchedByOrgB.isPlatformWide()).isTrue();
    }

    // ---------------------------------------------------------------------
    // 3.11 Property 28: Credential_Store accepts arbitrary named credential field sets
    // Validates: Requirements 11.4
    // ---------------------------------------------------------------------

    /**
     * Feature: ai-provider-flexibility, Property 28: Credential_Store accepts arbitrary named credential field sets
     * Validates: Requirements 11.4
     */
    @Property(tries = 100)
    @Tag("ai-provider-flexibility")
    @Tag("property-28")
    void arbitraryNamedCredentialFieldSetsRoundTripForSingleAndMultiFieldCatalogEntries(
            @ForAll("catalogPayloads") CatalogPayload payload) {
        AuthPrincipal principal = orgAdmin(createOrg());

        ProviderConfigService.ProviderConfigView created = service.create(
                principal, payload.providerType(), "Field Set Test " + payload.providerType(),
                payload.credentials(), Map.of());

        // Every declared credential field produces a hint; no schema-specific branching
        // breaks either the single-field (groq) or multi-field (aws_bedrock) shape.
        for (String key : payload.credentials().keySet()) {
            assertThat(created.credentialHints()).containsKey(key);
            String plaintext = payload.credentials().get(key);
            String hint = created.credentialHints().get(key);
            assertThat(hint).isNotEqualTo(plaintext);
            if (plaintext.length() >= 4) {
                assertThat(hint).endsWith(plaintext.substring(plaintext.length() - 4));
            }
        }

        // Update also round-trips for every declared field of either shape.
        ProviderConfigService.ProviderConfigView updated = service.update(
                principal, created.id(), null, payload.credentials(), null, null);
        for (String key : payload.credentials().keySet()) {
            assertThat(updated.credentialHints()).containsKey(key);
        }
    }
}
