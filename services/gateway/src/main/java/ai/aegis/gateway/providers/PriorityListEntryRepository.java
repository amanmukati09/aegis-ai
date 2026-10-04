package ai.aegis.gateway.providers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PriorityListEntryRepository extends JpaRepository<PriorityListEntry, UUID> {

    /** Org-scoped, feature-specific list, in admin-defined order. */
    List<PriorityListEntry> findByOrgIdAndFeatureOrderByPriorityOrderAsc(UUID orgId, String feature);

    /** Org-scoped, global list (applies to every feature without its own list). */
    List<PriorityListEntry> findByOrgIdAndFeatureIsNullOrderByPriorityOrderAsc(UUID orgId);

    /** Platform-wide, feature-specific list. */
    List<PriorityListEntry> findByOrgIdIsNullAndFeatureOrderByPriorityOrderAsc(String feature);

    /** Platform-wide, global list. */
    List<PriorityListEntry> findByOrgIdIsNullAndFeatureIsNullOrderByPriorityOrderAsc();

    void deleteByOrgIdAndFeature(UUID orgId, String feature);

    void deleteByOrgIdAndFeatureIsNull(UUID orgId);
}
