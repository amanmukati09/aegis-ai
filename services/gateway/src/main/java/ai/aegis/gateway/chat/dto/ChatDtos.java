package ai.aegis.gateway.chat.dto;

import ai.aegis.gateway.chat.ChatMessage;
import ai.aegis.gateway.chat.ChatSession;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record SessionView(String id, String title, OffsetDateTime createdAt) {
        public static SessionView of(ChatSession s) {
            return new SessionView(s.getId().toString(), s.getTitle(), s.getCreatedAt());
        }
    }

    public record MessageView(String id, String role, String content, OffsetDateTime createdAt) {
        public static MessageView of(ChatMessage m) {
            return new MessageView(m.getId().toString(), m.getRole(), m.getContent(), m.getCreatedAt());
        }
    }

    public record CreateSessionRequest(String title) {
    }

    public record RenameRequest(@NotBlank String title) {
    }

    public record SendRequest(String sessionId, @NotBlank String message, String provider, String model) {
    }

    public record SendResponse(String sessionId, String reply, String model) {
    }
}
