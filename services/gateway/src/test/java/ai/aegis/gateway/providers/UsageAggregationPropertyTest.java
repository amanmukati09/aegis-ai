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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests for {@link UsageRecordService}'s grouped-aggregation (task
 * 6.5) and daily-rollup (task 6.6) query methods, following the
 * Testcontainers + jqwik conventions established in
 * {@code UsageRecordServiceTest} / {@code GlobalDefaultServiceTest} /
 * {@code FeatureAssignmentServiceTest}.
 */
@JqwikSpringSupport
@SpringBootTest
class UsageAggregationPropertyTest {

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

    /** A (providerConfigurationId, modelEntryId) pair belonging to a given org. */
    record ValidPair(UUID providerConfigurationId, UUID modelEntryId) {
    }

    /**
     * Creates a real Provider_Configuration (+ its default Model_Entry) for
     * the given org via {@link ProviderConfigService}, since
     * usage_records.provider_configuration_id / model_entry_id are real FKs
     * (ON DELETE SET NULL) to provider_configurations / model_entries.
     */
    private ValidPair newValidPair(UUID orgId, UUID creatorUserId, String suffix) {
        AuthPrincipal principal = new AuthPrincipal(creatorUserId, orgId, "creator+" + creatorUserId + "@org.test", Role.ORG_ADMIN);
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("api_key", "gsk_" + suffix);
        ProviderConfigService.ProviderConfigView created = providerConfigService.create(
                principal, "groq", "Aggregation Fixture " + suffix, credentials, Map.of());
        UUID modelEntryId = created.modelEntries().get(0).id();
        return new ValidPair(created.id(), modelEntryId);
    }

    // ---------------------------------------------------------------------
    // Property 19: Grouped usage aggregation matches raw sums
    // ---------------------------------------------------------------------

    /** One raw usage record to feed into a (pairIndex, inputTokens, outputTokens) bucket. */
    record RawUsageSeed(int pairIndex, int inputTokens, int outputTokens) {
    }

    /**
     * Generates a small number of distinct pair-slots (1-5) and a list of
     * plain (pairIndex, inputTokens, outputTokens) seeds referencing those
     * slots by index (plain data only, no DB side effects).
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
     * therefore built from this plain seed list inside the @Property method
     * body instead (see below).
     */
    @Provide
    Arbitrary<UsageSeeds> usageSeeds() {
        Arbitrary<Integer> pairCount = Arbitraries.integers().between(1, 5);
        return pairCount.flatMap(count -> {
            Arbitrary<RawUsageSeed> seed = Arbitraries.integers().between(0, count - 1)
                    .flatMap(pairIndex -> Arbitraries.integers().between(0, 1_000_000)
                            .flatMap(inputTokens -> Arbitraries.integers().between(0, 1_000_000)
                                    .map(outputTokens -> new RawUsageSeed(pairIndex, inputTokens, outputTokens))));
            return seed.list().ofMinSize(1).ofMaxSize(15)
                    .map(seeds -> new UsageSeeds(count, seeds));
        });
    }

    record UsageSeeds(int pairCount, List<RawUsageSeed> seeds) {
    }

    /**
     * Feature: ai-provider-flexibility, Property 19: Grouped usage aggregation matches raw sums
     * Validates: Requirements 6.3
     */
    @Property(tries = 30)
    @Tag("ai-provider-flexibility")
    @Tag("property-19")
    void groupedAggregationByProviderAndModelMatchesRawSums(@ForAll("usageSeeds") UsageSeeds usageSeeds) {
        // DB-touching fixture setup lives here (not in @Provide -- see note above).
        UUID orgId = createOrg();
        UUID userId = createUser(orgId);

        List<ValidPair> pairs = new ArrayList<>();
        for (int i = 0; i < usageSeeds.pairCount(); i++) {
            pairs.add(newValidPair(orgId, userId, "p19-" + UUID.randomUUID() + "-" + i));
        }

        // Expected sums per pair, computed independently of the method under test.
        long[] expectedInput = new long[usageSeeds.pairCount()];
        long[] expectedOutput = new long[usageSeeds.pairCount()];
        long[] expectedCount = new long[usageSeeds.pairCount()];

        OffsetDateTime from = OffsetDateTime.now().minusMinutes(5);

        for (RawUsageSeed seed : usageSeeds.seeds()) {
            ValidPair pair = pairs.get(seed.pairIndex());
            usageRecordService.recordSuccess(
                    orgId, userId, pair.providerConfigurationId(), pair.modelEntryId(),
                    "chat", seed.inputTokens(), seed.outputTokens(), seed.pairIndex());
            expectedInput[seed.pairIndex()] += seed.inputTokens();
            expectedOutput[seed.pairIndex()] += seed.outputTokens();
            expectedCount[seed.pairIndex()] += 1;
        }

        OffsetDateTime to = OffsetDateTime.now().plusMinutes(5);

        List<UsageRecordService.ProviderModelUsageView> result =
                usageRecordService.aggregateByProviderAndModel(orgId, from, to);

        for (int i = 0; i < usageSeeds.pairCount(); i++) {
            if (expectedCount[i] == 0) {
                // No seed referenced this pair-slot; nothing to assert for it.
                continue;
            }
            ValidPair pair = pairs.get(i);
            UsageRecordService.ProviderModelUsageView matching = result.stream()
                    .filter(v -> v.providerConfigurationId().equals(pair.providerConfigurationId())
                            && v.modelEntryId().equals(pair.modelEntryId()))
                    .findFirst()
                    .orElse(null);
            assertThat(matching)
                    .as("aggregation row for pair-slot %d", i)
                    .isNotNull();
            assertThat(matching.totalInputTokens()).isEqualTo(expectedInput[i]);
            assertThat(matching.totalOutputTokens()).isEqualTo(expectedOutput[i]);
            assertThat(matching.callCount()).isEqualTo(expectedCount[i]);
        }
    }

    // ---------------------------------------------------------------------
    // Property 20: Daily rollup matches raw sums within range
    // ---------------------------------------------------------------------

    /** A known materialized rollup row: offset (days before "today") plus known totals. */
    record RollupSeed(int dayOffset, int inputTokens, int outputTokens, int callCount) {
    }

    /**
     * Generates a small list of distinct day-offsets (plain data only, no DB
     * side effects) paired with plain known totals. See the note on {@link
     * #usageSeeds()} for why this stays DB-free.
     */
    @Provide
    Arbitrary<List<RollupSeed>> rollupSeeds() {
        // Distinct day-offsets (0..19), generated as a non-empty subset of the
        // full offset range, so no two seeds ever target the same date for a
        // given org -- avoiding a primary-key collision on
        // usage_daily_rollups(org_id, rollup_date).
        List<Integer> allOffsets = new ArrayList<>();
        for (int i = 0; i <= 19; i++) {
            allOffsets.add(i);
        }
        Arbitrary<List<Integer>> offsetSubset = Arbitraries.of(allOffsets)
                .list().ofMinSize(1).ofMaxSize(allOffsets.size()).uniqueElements();

        // Independently generate one (inputTokens, outputTokens, callCount)
        // triple per offset, then zip them together positionally -- this
        // avoids relying on any flatMap/combine overload beyond what's
        // already used elsewhere in this test package.
        return offsetSubset.flatMap(offsets -> {
            Arbitrary<Integer> tokens = Arbitraries.integers().between(0, 1_000_000);
            Arbitrary<Integer> callCount = Arbitraries.integers().between(1, 500);
            Arbitrary<List<Integer>> inputTokensList = tokens.list().ofSize(offsets.size());
            return inputTokensList.flatMap(inputTokensValues -> {
                Arbitrary<List<Integer>> outputTokensList = tokens.list().ofSize(offsets.size());
                return outputTokensList.flatMap(outputTokensValues -> {
                    Arbitrary<List<Integer>> callCountList = callCount.list().ofSize(offsets.size());
                    return callCountList.map(callCountValues -> {
                        List<RollupSeed> result = new ArrayList<>();
                        for (int i = 0; i < offsets.size(); i++) {
                            result.add(new RollupSeed(
                                    offsets.get(i), inputTokensValues.get(i),
                                    outputTokensValues.get(i), callCountValues.get(i)));
                        }
                        return result;
                    });
                });
            });
        });
    }

    /**
     * Feature: ai-provider-flexibility, Property 20: Daily rollup matches raw sums within range
     * Validates: Requirements 6.4
     */
    @Property(tries = 30)
    @Tag("ai-provider-flexibility")
    @Tag("property-20")
    void dailyRollupMatchesMaterializedRowsWithinRequestedRange(@ForAll("rollupSeeds") List<RollupSeed> rollupSeeds) {
        // DB-touching fixture setup lives here (not in @Provide -- see note above).
        UUID orgId = createOrg();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // Insert every generated seed as a materialized usage_daily_rollups row,
        // spanning the full 0-19 day-offset space irrespective of which window
        // we'll later query -- this is what lets us assert that rows outside the
        // requested window are excluded.
        for (RollupSeed seed : rollupSeeds) {
            LocalDate date = today.minusDays(seed.dayOffset());
            jdbc.update(
                    "INSERT INTO usage_daily_rollups (org_id, rollup_date, input_tokens, output_tokens, call_count) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    orgId, date, seed.inputTokens(), seed.outputTokens(), seed.callCount());
        }

        // Query a window covering only the first half of the offset space
        // (day-offsets 0..9), so that some seeds fall inside and (whenever the
        // generated offsets reach into 10..19) some fall outside.
        LocalDate from = today.minusDays(9);
        LocalDate to = today;

        List<UsageRecordService.DailyRollupView> result = usageRecordService.dailyRollup(orgId, from, to);

        // (a) every inserted row within the window appears with matching values
        for (RollupSeed seed : rollupSeeds) {
            if (seed.dayOffset() > 9) {
                continue;
            }
            LocalDate date = today.minusDays(seed.dayOffset());
            UsageRecordService.DailyRollupView matching = result.stream()
                    .filter(v -> v.date().equals(date))
                    .findFirst()
                    .orElse(null);
            assertThat(matching).as("rollup row for date %s", date).isNotNull();
            assertThat(matching.inputTokens()).isEqualTo(seed.inputTokens());
            assertThat(matching.outputTokens()).isEqualTo(seed.outputTokens());
            assertThat(matching.callCount()).isEqualTo(seed.callCount());
        }

        // (b) rows outside the window do not appear
        for (RollupSeed seed : rollupSeeds) {
            if (seed.dayOffset() <= 9) {
                continue;
            }
            LocalDate date = today.minusDays(seed.dayOffset());
            assertThat(result.stream().anyMatch(v -> v.date().equals(date)))
                    .as("rollup row for out-of-window date %s must be excluded", date)
                    .isFalse();
        }

        // (c) the returned list is date-ascending ordered
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i - 1).date()).isBeforeOrEqualTo(result.get(i).date());
        }
    }
}
