package ai.aegis.gateway.chat;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.chat.dto.ChatDtos.MessageView;
import ai.aegis.gateway.chat.dto.ChatDtos.SendResponse;
import ai.aegis.gateway.chat.dto.ChatDtos.SessionView;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.guardrails.GuardrailService;
import ai.aegis.gateway.ml.MlClient;
import ai.aegis.gateway.ml.MlDtos.ChatRequest;
import ai.aegis.gateway.ml.MlDtos.ChatResponse;
import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat/copilot logic. Sessions and messages are owned by a user and persisted in the
 * gateway; the LLM call is delegated to the ML sidecar. User input is guardrail-masked
 * before it leaves for the model.
 */
@Service
public class ChatService {

    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final MlClient ml;
    private final GuardrailService guardrails;
    private final AuditService audit;

    public ChatService(ChatSessionRepository sessions, ChatMessageRepository messages,
                       MlClient ml, GuardrailService guardrails, AuditService audit) {
        this.sessions = sessions;
        this.messages = messages;
        this.ml = ml;
        this.guardrails = guardrails;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SessionView> listSessions(AuthPrincipal principal) {
        return sessions.findByUserIdOrderByCreatedAtDesc(principal.userId())
                .stream().map(SessionView::of).toList();
    }

    @Transactional
    public SessionView createSession(AuthPrincipal principal, String title) {
        ChatSession session = new ChatSession(UUID.randomUUID(), principal.orgId(), principal.userId(),
                (title == null || title.isBlank()) ? "New Incident Discussion" : title.trim());
        sessions.save(session);
        return SessionView.of(session);
    }

    @Transactional
    public SessionView rename(AuthPrincipal principal, UUID id, String title) {
        ChatSession session = requireSession(principal, id);
        session.setTitle(title.trim());
        sessions.save(session);
        return SessionView.of(session);
    }

    @Transactional
    public void delete(AuthPrincipal principal, UUID id) {
        ChatSession session = requireSession(principal, id);
        messages.deleteBySessionId(session.getId());
        sessions.delete(session);
    }

    @Transactional(readOnly = true)
    public List<MessageView> getMessages(AuthPrincipal principal, UUID sessionId) {
        requireSession(principal, sessionId);
        return messages.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream().map(MessageView::of).toList();
    }

    @Transactional
    public SendResponse send(AuthPrincipal principal, String sessionIdStr, String message,
                             String provider, String model, String ip) {
        // Guardrail 1 (input): block prompt-injection / jailbreak attempts.
        if (guardrails.isPromptInjection(message)) {
            audit.record(principal.orgId(), principal.userId(), principal.email(),
                    "chat_injection_blocked", "chat_session", sessionIdStr, ip);
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This request was blocked by AegisAI security guardrails.");
        }

        ChatSession session = resolveOrCreate(principal, sessionIdStr, message);

        // Guardrail 2 (transport): mask PII/secrets before storage + before it leaves for the LLM.
        String masked = guardrails.maskPii(message);
        messages.save(new ChatMessage(UUID.randomUUID(), session.getId(), "user", masked));

        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage m : messages.findBySessionIdOrderByCreatedAtAsc(session.getId())) {
            history.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }

        // Guardrail 3 (model): pass the security system prompt (defense in depth).
        ChatResponse resp = ml.chat(new ChatRequest(masked, history,
                guardrails.securitySystemPrompt(), provider, model));

        // Guardrail 4 (output): scan the model response for destructive content.
        String reply = resp.reply();
        if (guardrails.isDestructive(reply)) {
            reply = "\u26a0\ufe0f The generated response was withheld because it contained a "
                    + "potentially destructive command. Please rephrase your request.";
            audit.record(principal.orgId(), principal.userId(), principal.email(),
                    "chat_output_blocked", "chat_session", session.getId().toString(), ip);
        }

        messages.save(new ChatMessage(UUID.randomUUID(), session.getId(), "assistant", reply));
        audit.record(principal.orgId(), principal.userId(), principal.email(),
                "chat_message", "chat_session", session.getId().toString(), ip);

        return new SendResponse(session.getId().toString(), reply, resp.model());
    }

    /**
     * Prepare a streaming turn: run the input guardrail, persist the (masked) user message,
     * and build the ML chat request from the full history. Returns everything the controller
     * needs to open the SSE stream. Runs in its own transaction (commits before streaming).
     */
    @Transactional
    public StreamPrep prepareStream(AuthPrincipal principal, String sessionIdStr, String message,
                                    String provider, String model, String ip) {
        if (guardrails.isPromptInjection(message)) {
            audit.record(principal.orgId(), principal.userId(), principal.email(),
                    "chat_injection_blocked", "chat_session", sessionIdStr, ip);
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This request was blocked by AegisAI security guardrails.");
        }
        ChatSession session = resolveOrCreate(principal, sessionIdStr, message);
        String masked = guardrails.maskPii(message);
        messages.save(new ChatMessage(UUID.randomUUID(), session.getId(), "user", masked));

        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage m : messages.findBySessionIdOrderByCreatedAtAsc(session.getId())) {
            history.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        ChatRequest req = new ChatRequest(masked, history,
                guardrails.securitySystemPrompt(), provider, model);
        return new StreamPrep(session.getId(), req);
    }

    /**
     * Finalize a streaming turn: run the output guardrail on the assembled reply, persist the
     * assistant message, and audit. Returns the (possibly sanitized) reply actually stored.
     */
    @Transactional
    public String finalizeStream(AuthPrincipal principal, UUID sessionId, String assembledReply, String ip) {
        String reply = assembledReply == null ? "" : assembledReply;
        if (guardrails.isDestructive(reply)) {
            reply = "\u26a0\ufe0f The generated response was withheld because it contained a "
                    + "potentially destructive command. Please rephrase your request.";
            audit.record(principal.orgId(), principal.userId(), principal.email(),
                    "chat_output_blocked", "chat_session", sessionId.toString(), ip);
        }
        messages.save(new ChatMessage(UUID.randomUUID(), sessionId, "assistant", reply));
        audit.record(principal.orgId(), principal.userId(), principal.email(),
                "chat_message_stream", "chat_session", sessionId.toString(), ip);
        return reply;
    }

    /** Full-text search across the caller's own chat messages. */
    @Transactional(readOnly = true)
    public List<SearchHit> search(AuthPrincipal principal, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        return messages.searchByUser(principal.userId(), q).stream()
                .map(row -> new SearchHit(
                        row[0] == null ? "" : row[0].toString(),   // session id
                        row[1] == null ? "" : row[1].toString(),   // session title
                        row[2] == null ? "" : row[2].toString(),   // role
                        row[3] == null ? "" : row[3].toString()))  // snippet
                .toList();
    }

    /** Data the controller needs to open an SSE stream after the DB work is committed. */
    public record StreamPrep(UUID sessionId, ai.aegis.gateway.ml.MlDtos.ChatRequest mlRequest) {
    }

    public record SearchHit(String sessionId, String sessionTitle, String role, String snippet) {
    }

    private ChatSession resolveOrCreate(AuthPrincipal principal, String sessionIdStr, String firstMessage) {
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            return requireSession(principal, UUID.fromString(sessionIdStr));
        }
        // New conversation: title from the first message.
        String title = firstMessage.length() > 40 ? firstMessage.substring(0, 40) + "…" : firstMessage;
        ChatSession session = new ChatSession(UUID.randomUUID(), principal.orgId(), principal.userId(), title);
        return sessions.save(session);
    }

    private ChatSession requireSession(AuthPrincipal principal, UUID id) {
        return sessions.findByIdAndUserId(id, principal.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Chat session not found"));
    }
}
