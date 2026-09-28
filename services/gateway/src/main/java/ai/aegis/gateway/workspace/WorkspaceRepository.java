package ai.aegis.gateway.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
    List<Workspace> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<Workspace> findByIdAndOrgId(UUID id, UUID orgId);
}
