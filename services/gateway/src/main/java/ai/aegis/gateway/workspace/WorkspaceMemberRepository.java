package ai.aegis.gateway.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {
    List<WorkspaceMember> findByWorkspaceId(UUID workspaceId);

    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    void deleteByWorkspaceId(UUID workspaceId);

    void deleteByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    /** Every workspace a user belongs to — the visibility set for workspace-scoped incidents. */
    @Query("select wm.workspaceId from WorkspaceMember wm where wm.userId = :userId")
    List<UUID> findWorkspaceIdsByUserId(UUID userId);
}
