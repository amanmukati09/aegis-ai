package ai.aegis.gateway.providers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatureModelAssignmentRepository extends JpaRepository<FeatureModelAssignment, UUID> {

    List<FeatureModelAssignment> findByOrgId(UUID orgId);

    Optional<FeatureModelAssignment> findByOrgIdAndFeature(UUID orgId, String feature);

    void deleteByOrgIdAndFeature(UUID orgId, String feature);
}
