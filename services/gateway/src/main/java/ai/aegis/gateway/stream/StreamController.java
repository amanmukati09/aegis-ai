package ai.aegis.gateway.stream;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamRepository repository;
    private final AuditService audit;

    public StreamController(StreamRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public record CreateRequest(@NotBlank String name, String description, String sourceType) {
    }

    public record StreamView(String id, String name, String description, String sourceType,
                             String status, OffsetDateTime createdAt) {
        static StreamView of(StreamRegistration s) {
            return new StreamView(s.getId().toString(), s.getName(), s.getDescription(),
                    s.getSourceType(), s.getStatus(), s.getCreatedAt());
        }
    }

    @GetMapping
    public List<StreamView> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return repository.findByOrgIdOrderByCreatedAtDesc(principal.orgId())
                .stream().map(StreamView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public StreamView create(@AuthenticationPrincipal AuthPrincipal principal,
                             @Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        StreamRegistration s = new StreamRegistration(UUID.randomUUID(), principal.orgId(),
                req.name(), req.description(), req.sourceType(), principal.userId());
        repository.save(s);
        audit.record(principal.orgId(), principal.userId(), principal.email(),
                "stream_created", "stream", s.getId().toString(), clientIp(http));
        return StreamView.of(s);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    @Transactional
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        StreamRegistration s = repository.findByIdAndOrgId(id, principal.orgId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Stream not found"));
        repository.delete(s);
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank()) ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }
}
