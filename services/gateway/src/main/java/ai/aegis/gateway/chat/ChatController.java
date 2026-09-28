package ai.aegis.gateway.chat;

import ai.aegis.gateway.chat.dto.ChatDtos.CreateSessionRequest;
import ai.aegis.gateway.chat.dto.ChatDtos.MessageView;
import ai.aegis.gateway.chat.dto.ChatDtos.RenameRequest;
import ai.aegis.gateway.chat.dto.ChatDtos.SendRequest;
import ai.aegis.gateway.chat.dto.ChatDtos.SendResponse;
import ai.aegis.gateway.chat.dto.ChatDtos.SessionView;
import ai.aegis.gateway.ml.MlClient;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService service;
    private final MlClient ml;

    public ChatController(ChatService service, MlClient ml) {
        this.service = service;
        this.ml = ml;
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return ml.models();
    }

    @GetMapping("/sessions")
    public List<SessionView> listSessions(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.listSessions(principal);
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView createSession(@AuthenticationPrincipal AuthPrincipal principal,
                                     @RequestBody(required = false) CreateSessionRequest req) {
        return service.createSession(principal, req == null ? null : req.title());
    }

    @GetMapping("/sessions/{id}/messages")
    public List<MessageView> messages(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return service.getMessages(principal, id);
    }

    @PutMapping("/sessions/{id}/rename")
    public SessionView rename(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id,
                              @Valid @RequestBody RenameRequest req) {
        return service.rename(principal, id, req.title());
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        service.delete(principal, id);
    }

    @PostMapping(value = "/message", produces = MediaType.APPLICATION_JSON_VALUE)
    public SendResponse send(@AuthenticationPrincipal AuthPrincipal principal,
                             @Valid @RequestBody SendRequest req, HttpServletRequest http) {
        return service.send(principal, req.sessionId(), req.message(), req.provider(), req.model(), clientIp(http));
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
