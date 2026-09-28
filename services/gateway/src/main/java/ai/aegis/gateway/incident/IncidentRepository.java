package ai.aegis.gateway.incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    // Org-scoped access (the common path for org_admin / member).
    Page<Incident> findByOrgId(UUID orgId, Pageable pageable);

    Optional<Incident> findByIdAndOrgId(UUID id, UUID orgId);

    long countByOrgId(UUID orgId);

    long countByOrgIdAndStatus(UUID orgId, String status);

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

    interface CountByLabel {
        String getLabel();
        long getTotal();
    }
}
