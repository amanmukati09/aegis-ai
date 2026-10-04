package ai.aegis.gateway.providers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persists {@link UsageRecord} rows for successful and exhausted-failure LLM
 * calls (Req 6.1, 6.2). This service takes plain value parameters rather than
 * an {@link ai.aegis.gateway.security.AuthPrincipal} because its callers are
 * not interactively-authenticated admins: it is invoked by the internal
 * {@code POST /api/internal/usage} endpoint (task 14.3), which is reached by
 * ML_Service's FailoverEngine with org/user/provider/model values it already
 * resolved itself, authenticated via a service token rather than a user
 * session. Designing the method signatures this way means that endpoint can
 * delegate directly to these methods with no principal/authorization logic
 * in between.
 */
@Service
public class UsageRecordService {

    private final UsageRecordRepository usageRecords;
    private final UsageDailyRollupRepository dailyRollups;

    public UsageRecordService(UsageRecordRepository usageRecords, UsageDailyRollupRepository dailyRollups) {
        this.usageRecords = usageRecords;
        this.dailyRollups = dailyRollups;
    }

    // ---------------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------------

    /**
     * One (Provider_Configuration, Model_Entry) usage bucket (Req 6.3). Wraps
     * {@link UsageRecordRepository.ProviderModelUsage} so callers depend on a
     * concrete, serialization-friendly shape rather than a projection
     * interface backed by a JPA proxy.
     */
    public record ProviderModelUsageView(UUID providerConfigurationId, UUID modelEntryId,
                                          long totalInputTokens, long totalOutputTokens, long callCount) {
        static ProviderModelUsageView of(UsageRecordRepository.ProviderModelUsage p) {
            return new ProviderModelUsageView(
                    p.getProviderConfigurationId(),
                    p.getModelEntryId(),
                    nullToZero(p.getTotalInputTokens()),
                    nullToZero(p.getTotalOutputTokens()),
                    p.getCallCount());
        }
    }

    /** One user's usage bucket within an org (Req 6.3). */
    public record UserUsageView(UUID userId, long totalInputTokens, long totalOutputTokens, long callCount) {
        static UserUsageView of(UsageRecordRepository.UserUsage u) {
            return new UserUsageView(
                    u.getUserId(),
                    nullToZero(u.getTotalInputTokens()),
                    nullToZero(u.getTotalOutputTokens()),
                    u.getCallCount());
        }
    }

    /** One organization's usage bucket, for SUPER_ADMIN cross-org views (Req 6.3). */
    public record OrgUsageView(UUID orgId, long totalInputTokens, long totalOutputTokens, long callCount) {
        static OrgUsageView of(UsageRecordRepository.OrgUsage o) {
            return new OrgUsageView(
                    o.getOrgId(),
                    nullToZero(o.getTotalInputTokens()),
                    nullToZero(o.getTotalOutputTokens()),
                    o.getCallCount());
        }
    }

    /** One day's materialized rollup for a single org (Req 6.4). */
    public record DailyRollupView(LocalDate date, long inputTokens, long outputTokens, long callCount) {
        static DailyRollupView of(UsageDailyRollup r) {
            return new DailyRollupView(r.getRollupDate(), r.getInputTokens(), r.getOutputTokens(), r.getCallCount());
        }
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * Records a successful LLM call with its real token counts (Req 6.1).
     *
     * @param orgId                   organization that initiated the call
     * @param userId                  user who initiated the call
     * @param providerConfigurationId the Provider_Configuration that served the request
     * @param modelEntryId            the Model_Entry that served the request
     * @param feature                 the Feature the call was made for
     * @param inputTokens             real input token count reported by the provider
     * @param outputTokens            real output token count reported by the provider
     * @param servedPairIndex         index into the Priority_List of the pair that served the request
     */
    @Transactional
    public UsageRecord recordSuccess(UUID orgId, UUID userId, UUID providerConfigurationId,
                                      UUID modelEntryId, String feature, int inputTokens,
                                      int outputTokens, Integer servedPairIndex) {
        UsageRecord record = new UsageRecord(
                UUID.randomUUID(),
                orgId,
                userId,
                providerConfigurationId,
                modelEntryId,
                feature,
                inputTokens,
                outputTokens,
                true,
                servedPairIndex);
        return usageRecords.save(record);
    }

    /**
     * Records an LLM call that failed after the applicable Priority_List was
     * exhausted (Req 6.2). Token counts, provider/model attribution, and the
     * served-pair index are all left {@code null} -- there is no successful
     * attempt to attribute them to, and this method MUST NOT fabricate values
     * for an unsuccessful call.
     */
    @Transactional
    public UsageRecord recordExhaustedFailure(UUID orgId, UUID userId, String feature) {
        UsageRecord record = new UsageRecord(
                UUID.randomUUID(),
                orgId,
                userId,
                null,
                null,
                feature,
                null,
                null,
                false,
                null);
        return usageRecords.save(record);
    }

    // ---------------------------------------------------------------------
    // Grouped aggregation (Req 6.3)
    // ---------------------------------------------------------------------

    /**
     * Usage within {@code orgId}, grouped by (Provider_Configuration, Model_Entry),
     * for calls in {@code [from, to]}.
     */
    @Transactional(readOnly = true)
    public List<ProviderModelUsageView> aggregateByProviderAndModel(UUID orgId, OffsetDateTime from, OffsetDateTime to) {
        return usageRecords.aggregateByProviderAndModel(orgId, from, to).stream()
                .map(ProviderModelUsageView::of)
                .toList();
    }

    /**
     * Same grouping as {@link #aggregateByProviderAndModel}, but across every
     * organization. SUPER_ADMIN-only at the controller layer (Req 6.5-6.7).
     */
    @Transactional(readOnly = true)
    public List<ProviderModelUsageView> aggregateByProviderAndModelAcrossOrgs(OffsetDateTime from, OffsetDateTime to) {
        return usageRecords.aggregateByProviderAndModelAcrossOrgs(from, to).stream()
                .map(ProviderModelUsageView::of)
                .toList();
    }

    /** Usage within {@code orgId}, grouped by user, for calls in {@code [from, to]}. */
    @Transactional(readOnly = true)
    public List<UserUsageView> aggregateByUser(UUID orgId, OffsetDateTime from, OffsetDateTime to) {
        return usageRecords.aggregateByUser(orgId, from, to).stream()
                .map(UserUsageView::of)
                .toList();
    }

    /**
     * Usage across every organization, grouped by org, for calls in
     * {@code [from, to]}. SUPER_ADMIN-only at the controller layer (Req 6.5-6.7).
     */
    @Transactional(readOnly = true)
    public List<OrgUsageView> aggregateByOrg(OffsetDateTime from, OffsetDateTime to) {
        return usageRecords.aggregateByOrg(from, to).stream()
                .map(OrgUsageView::of)
                .toList();
    }

    // ---------------------------------------------------------------------
    // Daily rollup (Req 6.4)
    // ---------------------------------------------------------------------

    /** Materialized per-day rollup for a single org, across {@code [from, to]}, date-ascending. */
    @Transactional(readOnly = true)
    public List<DailyRollupView> dailyRollup(UUID orgId, LocalDate from, LocalDate to) {
        return dailyRollups.findByIdOrgIdAndIdRollupDateBetweenOrderByIdRollupDateAsc(orgId, from, to).stream()
                .map(DailyRollupView::of)
                .toList();
    }

    /**
     * Materialized per-day rollup across every organization, across
     * {@code [from, to]}, date-ascending. SUPER_ADMIN-only at the controller
     * layer (Req 6.5-6.7).
     */
    @Transactional(readOnly = true)
    public List<DailyRollupView> dailyRollupAcrossOrgs(LocalDate from, LocalDate to) {
        return dailyRollups.findByIdRollupDateBetweenOrderByIdRollupDateAsc(from, to).stream()
                .map(DailyRollupView::of)
                .toList();
    }
}
