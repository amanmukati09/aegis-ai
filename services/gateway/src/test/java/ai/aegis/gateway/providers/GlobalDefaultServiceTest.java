package ai.aegis.gateway.providers;

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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for {@link GlobalDefaultService} (tasks.md 5.4), following the
 * Testcontainers + jqwik conventions established in
 * {@code ProviderConfigServiceTest} / {@code UsageRecordServiceTest}.
 */
@JqwikSpringSupport
@SpringBootTest
class GlobalDefaultServiceTest {

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
    GlobalDefaultService globalDefaultService;

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
     * Inserts a fresh organization row and returns its id. Both pairs
     * produced for a single property evaluation must belong to the SAME
     * org (the Global_Default being tested is per-org), so a fresh org is
     * created once per generated {@link TwoPairs} and threaded through
     * both {@link ValidPair}s and returned to the {@code @Property} method
     * via {@link TwoPairs#orgId()}.
     */
    private UUID createOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                orgId, "Org " + orgId, "org-" + orgId);
        return orgId;
    }

    /** A (providerConfigurationId, modelEntryId) pair belonging to the given org. */
    record ValidPair(UUID providerConfigurationId, UUID modelEntryId) {
    }

    private ValidPair newValidPair(UUID orgId, String suffix) {
        AuthPrincipal principal = orgAdmin(orgId);
        ProviderConfigService.ProviderConfigView config = providerConfigService.create(
                principal, "groq", "GlobalDefault Fixture " + suffix,
                groqCredentials("gsk_" + suffix), Map.of());
        UUID modelEntryId = config.modelEntries().get(0).id();
        return new ValidPair(config.id(), modelEntryId);
    }

    /**
     * Two distinct key suffixes, used to build two distinct
     * (Provider_Configuration, Model_Entry) fixture pairs inside the
     * {@code @Property} method body itself.
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
     * reported against jqwik's Quarkus support.
     */
    @Provide
    Arbitrary<TwoKeySuffixes> twoDistinctKeySuffixes() {
        Arbitrary<String> keySuffix = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(20);
        return Combinators.combine(keySuffix, keySuffix)
                .as(TwoKeySuffixes::new)
                .filter(pair -> !pair.suffixA().equals(pair.suffixB()));
    }

    record TwoKeySuffixes(String suffixA, String suffixB) {
    }

    record TwoPairs(UUID orgId, ValidPair first, ValidPair second) {
    }

    /**
     * Feature: ai-provider-flexibility, Property 14: Global_Default set/replace invariant
     * Validates: Requirements 5.1
     */
    @Property(tries = 50)
    @Tag("ai-provider-flexibility")
    @Tag("property-14")
    void settingGlobalDefaultReturnsExactPairAndFullyReplacesPrevious(@ForAll("twoDistinctKeySuffixes") TwoKeySuffixes suffixes) {
        // DB-touching fixture setup lives here (not in @Provide -- see note above).
        UUID orgId = createOrg();
        ValidPair first = newValidPair(orgId, suffixes.suffixA());
        ValidPair second = newValidPair(orgId, suffixes.suffixB());
        TwoPairs pairs = new TwoPairs(orgId, first, second);

        AuthPrincipal principal = orgAdmin(pairs.orgId());

        // Setting the first pair as Global_Default and reading it back returns exactly that pair.
        GlobalDefaultService.GlobalDefaultView firstSet = globalDefaultService.setOrgDefault(
                principal, pairs.first().providerConfigurationId(), pairs.first().modelEntryId());
        assertThat(firstSet.providerConfigurationId()).isEqualTo(pairs.first().providerConfigurationId());
        assertThat(firstSet.modelEntryId()).isEqualTo(pairs.first().modelEntryId());

        Optional<GlobalDefaultService.GlobalDefaultView> readBack = globalDefaultService.getOrgDefault(principal);
        assertThat(readBack).isPresent();
        assertThat(readBack.get().providerConfigurationId()).isEqualTo(pairs.first().providerConfigurationId());
        assertThat(readBack.get().modelEntryId()).isEqualTo(pairs.first().modelEntryId());

        // Setting a new Global_Default fully replaces the previous one: at most
        // one Global_Default per organization at any time.
        GlobalDefaultService.GlobalDefaultView secondSet = globalDefaultService.setOrgDefault(
                principal, pairs.second().providerConfigurationId(), pairs.second().modelEntryId());
        assertThat(secondSet.providerConfigurationId()).isEqualTo(pairs.second().providerConfigurationId());
        assertThat(secondSet.modelEntryId()).isEqualTo(pairs.second().modelEntryId());

        Optional<GlobalDefaultService.GlobalDefaultView> readBackAfterReplace = globalDefaultService.getOrgDefault(principal);
        assertThat(readBackAfterReplace).isPresent();
        assertThat(readBackAfterReplace.get().providerConfigurationId()).isEqualTo(pairs.second().providerConfigurationId());
        assertThat(readBackAfterReplace.get().modelEntryId()).isEqualTo(pairs.second().modelEntryId());

        // Exactly one row exists for this org (upsert, not a second insert).
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM global_default_assignments WHERE org_id = ?", Integer.class, pairs.orgId());
        assertThat(rowCount).isEqualTo(1);
    }
}
