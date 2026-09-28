package ai.aegis.gateway.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AsyncJobRepository extends JpaRepository<AsyncJob, UUID> {
    Optional<AsyncJob> findByIdAndOrgId(UUID id, UUID orgId);
}
