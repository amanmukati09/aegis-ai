package ai.aegis.gateway.providers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GlobalDefaultAssignmentRepository extends JpaRepository<GlobalDefaultAssignment, UUID> {

    Optional<GlobalDefaultAssignment> findByOrgId(UUID orgId);
}
