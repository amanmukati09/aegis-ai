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

    /**
     * Same keyword search, but additionally filtered to articles whose source incident
     * is visible to the caller (unscoped, or in a workspace they belong to). A KB
     * article generated from a workspace-scoped incident the caller can't see must not
     * leak through search — this was a real gap: {@link #search} alone ignores
     * workspace membership entirely. Articles with no source incident (manually
     * authored, sourceIncidentId null) are treated as org-wide/visible to everyone,
     * matching how they already behave in list()/search() today.
     */
    @Query(value = """
            SELECT CAST(a.id AS text), a.title, a.category, a.difficulty,
                   left(coalesce(a.solution, ''), 200) AS snippet
            FROM kb_articles a
            LEFT JOIN incidents i ON i.id = a.source_incident_id
            WHERE a.org_id = :orgId
              AND (a.title ILIKE ('%' || :q || '%')
                   OR a.symptoms ILIKE ('%' || :q || '%')
                   OR a.root_cause ILIKE ('%' || :q || '%')
                   OR a.solution ILIKE ('%' || :q || '%')
                   OR a.tags ILIKE ('%' || :q || '%'))
              AND (a.source_incident_id IS NULL
                   OR i.workspace_id IS NULL
                   OR i.workspace_id IN (:memberWorkspaceIds))
            ORDER BY a.created_at DESC
            LIMIT 30
            """, nativeQuery = true)
    List<Object[]> searchVisible(UUID orgId, String q, List<UUID> memberWorkspaceIds);

    // --- pgvector similarity (real RAG for Copilot's knowledge-base tool) ---
    // Same embedding pipeline incidents already use (MlClient.embed -> 384-dim vector),
    // so semantically related articles are found even without exact keyword overlap —
    // e.g. asking about "checkout failing" surfaces an article titled "Payment service
    // 500s" if the content is conceptually similar.

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE kb_articles SET embedding = CAST(:vec AS vector) WHERE id = :id", nativeQuery = true)
    void setEmbedding(UUID id, String vec);

    /**
     * Nearest KB articles by cosine distance, org-scoped, admin/unrestricted variant.
     * Returns Object[] rows [id, title, category, difficulty, snippet, score].
     */
    @Query(value = """
            SELECT CAST(id AS text), title, category, difficulty,
                   left(coalesce(solution, ''), 200) AS snippet,
                   (1 - (embedding <=> CAST(:vec AS vector))) AS score
            FROM kb_articles
            WHERE org_id = :orgId AND embedding IS NOT NULL
            ORDER BY embedding <=> CAST(:vec AS vector)
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> searchSimilar(UUID orgId, String vec);

    /** Same similarity search, restricted to articles whose source incident is visible
     * to a non-admin caller — mirrors {@link #searchVisible}'s workspace join/filter. */
    @Query(value = """
            SELECT CAST(a.id AS text), a.title, a.category, a.difficulty,
                   left(coalesce(a.solution, ''), 200) AS snippet,
                   (1 - (a.embedding <=> CAST(:vec AS vector))) AS score
            FROM kb_articles a
            LEFT JOIN incidents i ON i.id = a.source_incident_id
            WHERE a.org_id = :orgId AND a.embedding IS NOT NULL
              AND (a.source_incident_id IS NULL
                   OR i.workspace_id IS NULL
                   OR i.workspace_id IN (:memberWorkspaceIds))
            ORDER BY a.embedding <=> CAST(:vec AS vector)
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> searchSimilarVisible(UUID orgId, String vec, List<UUID> memberWorkspaceIds);
}
