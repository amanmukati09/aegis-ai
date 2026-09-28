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

    /**
     * Stream a chat turn token-by-token over SSE.
     *
     * The DB work (input guardrail, persist user message, build history) is done up front and
     * committed before we open the stream. Tokens from the ML sidecar are relayed as they
     * arrive; the assembled reply is accumulated, output-guardrailed, and persisted when the
     * upstream stream completes. The client receives:
     *   event: session  -> the session id (first, so a new session can be tracked)
     *   data: <token>    -> incremental content chunks
     *   data: [DONE]     -> terminal marker
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> stream(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SendRequest req, HttpServletRequest http) {

        String ip = clientIp(http);
        // Blocking prep runs on the request (virtual) thread before the reactive stream starts.
        ChatService.StreamPrep prep = service.prepareStream(
                principal, req.sessionId(), req.message(), req.provider(), req.model(), ip);

        StringBuilder assembled = new StringBuilder();

        var sessionEvent = reactor.core.publisher.Flux.just(
                org.springframework.http.codec.ServerSentEvent.<String>builder()
                        .event("session").data(prep.sessionId().toString()).build());

        var tokenEvents = ml.chatStream(prep.mlRequest())
                .filter(chunk -> chunk != null && !chunk.equals("[DONE]"))
                .doOnNext(assembled::append)
                .map(chunk -> org.springframework.http.codec.ServerSentEvent.<String>builder()
                        .data(chunk).build());

        // After the token stream completes, finalize (persist + output guardrail) off the
        // event loop, then emit the terminal marker.
        var doneEvent = reactor.core.publisher.Mono.fromCallable(() -> {
                    service.finalizeStream(principal, prep.sessionId(), assembled.toString(), ip);
                    return org.springframework.http.codec.ServerSentEvent.<String>builder()
                            .data("[DONE]").build();
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());

        return sessionEvent.concatWith(tokenEvents).concatWith(doneEvent);
    }

    @GetMapping("/search")
    public List<ChatService.SearchHit> search(@AuthenticationPrincipal AuthPrincipal principal,
                                              @org.springframework.web.bind.annotation.RequestParam("q") String q) {
        return service.search(principal, q);
    }

    /** Lexicon tone/urgency of a message (used by the UI to badge operator sentiment). */
    @PostMapping(value = "/sentiment", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> sentiment(@RequestBody Map<String, String> body) {
        Map<String, Object> r = ml.sentiment(body == null ? "" : body.getOrDefault("text", ""));
        return r == null ? Map.of("label", "neutral", "score", 0.0, "urgency", "normal") : r;
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
