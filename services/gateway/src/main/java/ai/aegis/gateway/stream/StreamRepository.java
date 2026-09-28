package ai.aegis.gateway.stream;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StreamRepository extends JpaRepository<StreamRegistration, UUID> {
    List<StreamRegistration> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<StreamRegistration> findByIdAndOrgId(UUID id, UUID orgId);
}
