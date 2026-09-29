package ai.aegis.gateway.incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    // Org-scoped access for admins (super_admin/org_admin), who see every incident
    // regardless of workspace membership.
    Page<Incident> findByOrgId(UUID orgId, Pageable pageable);

    /** Same, additionally filtered to a workspace — the real "view" workspaces provide. */
    Page<Incident> findByOrgIdAndWorkspaceId(UUID orgId, UUID workspaceId, Pageable pageable);

    Optional<Incident> findByIdAndOrgId(UUID id, UUID orgId);

    long countByOrgId(UUID orgId);

    long countByOrgIdAndStatus(UUID orgId, String status);

    // --- Workspace-visibility-aware reads (for regular members) ---
    // An incident is visible to a regular member if it's unscoped (workspace_id IS NULL,
    // the shared/general pool) OR scoped to a workspace they belong to. Admins bypass this
    // entirely and use the plain findByOrgId methods above.

    @Query("select i from Incident i where i.orgId = :orgId " +
            "and (i.workspaceId is null or i.workspaceId in :memberWorkspaceIds)")
    Page<Incident> findVisibleByOrgId(UUID orgId, List<UUID> memberWorkspaceIds, Pageable pageable);

    @Query("select i from Incident i where i.orgId = :orgId " +
            "and (i.workspaceId is null or i.workspaceId in :memberWorkspaceIds) " +
            "order by i.detectedAt desc")
    List<Incident> findTop10VisibleByOrgId(UUID orgId, List<UUID> memberWorkspaceIds, Pageable pageable);

    @Query("select count(i) from Incident i where i.orgId = :orgId " +
            "and (i.workspaceId is null or i.workspaceId in :memberWorkspaceIds)")
    long countVisibleByOrgId(UUID orgId, List<UUID> memberWorkspaceIds);

    @Query("select count(i) from Incident i where i.orgId = :orgId and i.status = :status " +
            "and (i.workspaceId is null or i.workspaceId in :memberWorkspaceIds)")
    long countVisibleByOrgIdAndStatus(UUID orgId, String status, List<UUID> memberWorkspaceIds);

    // Aggregates for the dashboard.
    @Query("select i.status as label, count(i) as total from Incident i where i.orgId = :orgId group by i.status")
    List<CountByLabel> countGroupByStatus(UUID orgId);

    @Query("select coalesce(i.severity, 'unknown') as label, count(i) as total from Incident i where i.orgId = :orgId group by i.severity")
    List<CountByLabel> countGroupBySeverity(UUID orgId);

    // Mean time to resolution (hours) for resolved incidents in an org. Native query
    // because it uses Postgres' extract(epoch ...) on a timestamp difference.
    @Query(value = "select avg(extract(epoch from (resolved_at - detected_at)) / 3600.0) " +
            "from incidents where org_id = :orgId and resolved_at is not null", nativeQuery = true)
    Double avgResolutionHours(UUID orgId);

    List<Incident> findTop10ByOrgIdOrderByDetectedAtDesc(UUID orgId);

    // --- pgvector similarity ---
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE incidents SET embedding = CAST(:vec AS vector) WHERE id = :id", nativeQuery = true)
    void setEmbedding(UUID id, String vec);

    /**
     * Nearest incidents by cosine distance (<=>) within an org, excluding self.
     * Returns Object[] rows [id, title, severity, status, score] — an Object[] projection
     * is more robust than an interface projection for computed native columns.
     */
    @Query(value = """
            SELECT CAST(id AS text), title, severity, status,
                   (1 - (embedding <=> CAST(:vec AS vector))) AS score
            FROM incidents
            WHERE org_id = :orgId AND embedding IS NOT NULL AND id <> :selfId
            ORDER BY embedding <=> CAST(:vec AS vector)
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> findSimilar(UUID orgId, UUID selfId, String vec);

    /** Same similarity search, restricted to incidents visible to a non-admin caller. */
    @Query(value = """
            SELECT CAST(id AS text), title, severity, status,
                   (1 - (embedding <=> CAST(:vec AS vector))) AS score
            FROM incidents
            WHERE org_id = :orgId AND embedding IS NOT NULL AND id <> :selfId
              AND (workspace_id IS NULL OR workspace_id IN (:memberWorkspaceIds))
            ORDER BY embedding <=> CAST(:vec AS vector)
            LIMIT 5
            """, nativeQuery = true)
    List<Object[]> findSimilarVisible(UUID orgId, UUID selfId, String vec, List<UUID> memberWorkspaceIds);

    interface CountByLabel {
        String getLabel();
        long getTotal();
    }
}
