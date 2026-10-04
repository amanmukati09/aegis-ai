package ai.aegis.gateway.providers;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    List<UsageRecord> findByOrgIdAndCreatedAtBetween(UUID orgId, OffsetDateTime from, OffsetDateTime to);

    List<UsageRecord> findByCreatedAtBetween(OffsetDateTime from, OffsetDateTime to);

    // --- Grouped aggregation (Req 6.3) ---
    // Org-scoped aggregation by (provider_configuration, model_entry); SUPER_ADMIN-only
    // cross-org aggregation is provided by the analogous *WithoutOrg query below.

    @Query("select r.providerConfigurationId as providerConfigurationId, " +
            "r.modelEntryId as modelEntryId, " +
            "sum(r.inputTokens) as totalInputTokens, " +
            "sum(r.outputTokens) as totalOutputTokens, " +
            "count(r) as callCount " +
            "from UsageRecord r where r.orgId = :orgId and r.createdAt between :from and :to " +
            "group by r.providerConfigurationId, r.modelEntryId")
    List<ProviderModelUsage> aggregateByProviderAndModel(UUID orgId, OffsetDateTime from, OffsetDateTime to);

    @Query("select r.providerConfigurationId as providerConfigurationId, " +
            "r.modelEntryId as modelEntryId, " +
            "sum(r.inputTokens) as totalInputTokens, " +
            "sum(r.outputTokens) as totalOutputTokens, " +
            "count(r) as callCount " +
            "from UsageRecord r where r.createdAt between :from and :to " +
            "group by r.providerConfigurationId, r.modelEntryId")
    List<ProviderModelUsage> aggregateByProviderAndModelAcrossOrgs(OffsetDateTime from, OffsetDateTime to);

    @Query("select r.userId as userId, " +
            "sum(r.inputTokens) as totalInputTokens, " +
            "sum(r.outputTokens) as totalOutputTokens, " +
            "count(r) as callCount " +
            "from UsageRecord r where r.orgId = :orgId and r.createdAt between :from and :to " +
            "group by r.userId")
    List<UserUsage> aggregateByUser(UUID orgId, OffsetDateTime from, OffsetDateTime to);

    @Query("select r.orgId as orgId, " +
            "sum(r.inputTokens) as totalInputTokens, " +
            "sum(r.outputTokens) as totalOutputTokens, " +
            "count(r) as callCount " +
            "from UsageRecord r where r.createdAt between :from and :to " +
            "group by r.orgId")
    List<OrgUsage> aggregateByOrg(OffsetDateTime from, OffsetDateTime to);

    interface ProviderModelUsage {
        UUID getProviderConfigurationId();
        UUID getModelEntryId();
        Long getTotalInputTokens();
        Long getTotalOutputTokens();
        long getCallCount();
    }

    interface UserUsage {
        UUID getUserId();
        Long getTotalInputTokens();
        Long getTotalOutputTokens();
        long getCallCount();
    }

    interface OrgUsage {
        UUID getOrgId();
        Long getTotalInputTokens();
        Long getTotalOutputTokens();
        long getCallCount();
    }
}
