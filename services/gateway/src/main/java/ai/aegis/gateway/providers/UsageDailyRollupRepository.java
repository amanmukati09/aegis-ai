package ai.aegis.gateway.providers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsageDailyRollupRepository extends JpaRepository<UsageDailyRollup, UsageDailyRollup.Key> {

    // org_id and rollup_date live inside the @EmbeddedId Key, not as direct
    // UsageDailyRollup fields -- Spring Data's derived-query parser needs the
    // "Id_<EmbeddedProperty>" traversal syntax to resolve them (plain
    // "OrgId"/"RollupDate" fails at startup with a PathElementException,
    // since those attribute names do not exist directly on UsageDailyRollup).
    List<UsageDailyRollup> findByIdOrgIdAndIdRollupDateBetweenOrderByIdRollupDateAsc(
            UUID orgId, LocalDate from, LocalDate to);

    List<UsageDailyRollup> findByIdRollupDateBetweenOrderByIdRollupDateAsc(LocalDate from, LocalDate to);

    Optional<UsageDailyRollup> findByIdOrgIdAndIdRollupDate(UUID orgId, LocalDate rollupDate);
}
