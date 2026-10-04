package ai.aegis.gateway.providers;

import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.user.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for {@link FeatureAssignmentService} (tasks.md 5.5), following
 * the Testcontainers + jqwik conventions established in
 * {@code ProviderConfigServiceTest} / {@code GlobalDefaultServiceTest}.
 */
@JqwikSpringSupport
@SpringBootTest
class FeatureAssignmentServiceTest {

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
    JdbcTemplate jdbc;

    @Autowired
    ProviderConfigService providerConfigService;

    @Autowired
    FeatureAssignmentService featureAssignmentService;

    private AuthPrincipal orgAdmin(UUID orgId) {
        UUID userId = UUID.randomUUID();
        String email = "admin+" + userId + "@org.test";
        jdbc.update(
                "INSERT INTO users (id, org_id, email, full_name, role) VALUES (?, ?, ?, ?, ?)",
                userId, orgId, email, "Org Admin", "org_admin");
        return new AuthPrincipal(userId, orgId, email, Role.ORG_ADMIN);
    }

    private static Map<String, String> groqCredentials(String apiKey) {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("api_key", apiKey);
        return creds;
    }

    /**
     * Inserts a fresh organization row and returns its id. A fresh org is
     * created once per generated sample and threaded through
     * {@link FeatureSubset#orgId()} so the {@code @Property} method body
     * can keep using the SAME org for its own fixture pairs.
     */
    private UUID createOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                orgId, "Org " + orgId, "org-" + orgId);
        return orgId;
    }

    record ValidPair(UUID providerConfigurationId, UUID modelEntryId) {
    }

    private ValidPair newValidPairForOrg(UUID orgId, String suffix) {
        AuthPrincipal principal = orgAdmin(orgId);
        ProviderConfigService.ProviderConfigView config = providerConfigService.create(
                principal, "groq", "FeatureAssignment Fixture " + suffix,
                groqCredentials("gsk_" + suffix), Map.of());
        UUID modelEntryId = config.modelEntries().get(0).id();
        return new ValidPair(config.id(), modelEntryId);
    }

    /** The generated org plus a non-empty subset of Features, each paired with its own distinct fixture pair. */
    record FeatureSubset(UUID orgId, Map<String, ValidPair> featureToPair) {
    }

    /**
     * Generates a non-empty subset of the named Features only (plain data,
     * no DB side effects).
     *
     * NOTE: this {@code @Provide} method deliberately does NOT touch
     * {@code jdbc}/{@code providerConfigService} (both {@code @Autowired}).
     * jqwik-spring's documented support only resolves @Autowired for
     * @Property/@BeforeProperty methods, constructor params, and lifecycle
     * hooks -- NOT for @Provide generator methods, which run on a separate,
     * non-Spring-managed instance. Calling an @Autowired field from inside
     * @Provide throws NullPointerException. See jqwik-spring README
     * ("Parameter Resolution of Autowired Beans") and
     * https://stackoverflow.com/questions/74388233 for the same limitation
     * reported against jqwik's Quarkus support. The org + fixture pairs are
     * therefore built from this plain feature list inside the @Property
     * method body instead (see below).
     */
    @Provide
    Arbitrary<List<String>> featureNameSubset() {
        List<String> allFeatures = new ArrayList<>(FeatureAssignmentService.VALID_FEATURES);
        return Arbitraries.of(allFeatures)
                .list().ofMinSize(2).ofMaxSize(allFeatures.size()).uniqueElements();
    }

    /**
     * Feature: ai-provider-flexibility, Property 16: Feature assignments do not interfere across features
     * Validates: Requirements 5.4
     */
    @Property(tries = 30)
    @Tag("ai-provider-flexibility")
    @Tag("property-16")
    void assigningEachFeatureIndependentlyDoesNotAffectOtherFeatureAssignments(
            @ForAll("featureNameSubset") List<String> features) {
        // DB-touching fixture setup lives here (not in @Provide -- see note above).
        UUID orgId = createOrg();
        Map<String, ValidPair> featureToPair = new LinkedHashMap<>();
        int i = 0;
        for (String feature : features) {
            featureToPair.put(feature, newValidPairForOrg(orgId, feature + "-" + UUID.randomUUID() + "-" + (i++)));
        }
        AuthPrincipal principal = orgAdmin(orgId);

        // Assign each feature in the subset to its own distinct pair.
        for (Map.Entry<String, ValidPair> entry : featureToPair.entrySet()) {
            featureAssignmentService.setAssignment(
                    principal, entry.getKey(),
                    entry.getValue().providerConfigurationId(), entry.getValue().modelEntryId());
        }

        // Reading back returns exactly those assignments.
        Map<String, FeatureAssignmentService.AssignmentView> listed = featureAssignmentService.listAssignments(principal);
        for (Map.Entry<String, ValidPair> entry : featureToPair.entrySet()) {
            FeatureAssignmentService.AssignmentView view = listed.get(entry.getKey());
            assertThat(view).isNotNull();
            assertThat(view.providerConfigurationId()).isEqualTo(entry.getValue().providerConfigurationId());
            assertThat(view.modelEntryId()).isEqualTo(entry.getValue().modelEntryId());
        }

        // Changing one feature's assignment doesn't affect the others.
        String changedFeature = featureToPair.keySet().iterator().next();
        ValidPair replacement = newValidPairForOrg(orgId, "replacement-" + UUID.randomUUID());
        featureAssignmentService.setAssignment(
                principal, changedFeature, replacement.providerConfigurationId(), replacement.modelEntryId());

        Map<String, FeatureAssignmentService.AssignmentView> afterChange = featureAssignmentService.listAssignments(principal);
        assertThat(afterChange.get(changedFeature).providerConfigurationId()).isEqualTo(replacement.providerConfigurationId());
        assertThat(afterChange.get(changedFeature).modelEntryId()).isEqualTo(replacement.modelEntryId());
        for (Map.Entry<String, ValidPair> entry : featureToPair.entrySet()) {
            if (entry.getKey().equals(changedFeature)) {
                continue;
            }
            FeatureAssignmentService.AssignmentView view = afterChange.get(entry.getKey());
            assertThat(view.providerConfigurationId()).isEqualTo(entry.getValue().providerConfigurationId());
            assertThat(view.modelEntryId()).isEqualTo(entry.getValue().modelEntryId());
        }

        // Removing one feature's assignment doesn't affect the others.
        String removedFeature = featureToPair.keySet().iterator().next();
        featureAssignmentService.removeAssignment(principal, removedFeature);

        Map<String, FeatureAssignmentService.AssignmentView> afterRemoval = featureAssignmentService.listAssignments(principal);
        assertThat(afterRemoval).doesNotContainKey(removedFeature);
        Set<String> remainingFeatures = new LinkedHashSet<>(featureToPair.keySet());
        remainingFeatures.remove(removedFeature);
        for (String feature : remainingFeatures) {
            ValidPair expected = feature.equals(changedFeature) ? replacement : featureToPair.get(feature);
            FeatureAssignmentService.AssignmentView view = afterRemoval.get(feature);
            assertThat(view).isNotNull();
            assertThat(view.providerConfigurationId()).isEqualTo(expected.providerConfigurationId());
            assertThat(view.modelEntryId()).isEqualTo(expected.modelEntryId());
        }
    }
}
