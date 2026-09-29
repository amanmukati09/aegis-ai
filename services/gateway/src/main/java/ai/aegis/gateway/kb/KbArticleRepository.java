package ai.aegis.gateway.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface KbArticleRepository extends JpaRepository<KbArticle, UUID> {

    List<KbArticle> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    boolean existsBySourceIncidentId(UUID sourceIncidentId);

    /**
     * Simple keyword relevance search: counts how many times the query words appear across
     * title/symptoms/root_cause/solution/tags, org-scoped. No vector store needed for a KB
     * this size; swappable for pgvector similarity later using the same embedding pattern
     * incidents already use.
     */
    @Query(value = """
            SELECT CAST(id AS text), title, category, difficulty,
                   left(coalesce(solution, ''), 200) AS snippet
            FROM kb_articles
            WHERE org_id = :orgId
              AND (title ILIKE ('%' || :q || '%')
                   OR symptoms ILIKE ('%' || :q || '%')
                   OR root_cause ILIKE ('%' || :q || '%')
                   OR solution ILIKE ('%' || :q || '%')
                   OR tags ILIKE ('%' || :q || '%'))
            ORDER BY created_at DESC
            LIMIT 30
            """, nativeQuery = true)
    List<Object[]> search(UUID orgId, String q);
}
