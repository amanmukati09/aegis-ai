package ai.aegis.gateway.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    void deleteBySessionId(UUID sessionId);

    /**
     * Case-insensitive search across the caller's own chat messages. Returns rows of
     * [session_id, session_title, role, snippet] with the matched content truncated.
     * Scoped by user_id so a caller only ever searches their own conversations.
     */
    @Query(value = """
            SELECT CAST(s.id AS text), s.title, m.role,
                   left(m.content, 240) AS snippet
            FROM chat_messages m
            JOIN chat_sessions s ON s.id = m.session_id
            WHERE s.user_id = :userId
              AND m.content ILIKE ('%' || :q || '%')
            ORDER BY m.created_at DESC
            LIMIT 30
            """, nativeQuery = true)
    List<Object[]> searchByUser(UUID userId, String q);
}
