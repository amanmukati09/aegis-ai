package ai.aegis.gateway.workflow;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Privileged remediation command execution — SUPER_ADMIN only, allowlisted, and fully
 * audited. Only read-only diagnostic commands from a fixed safe list may run; anything
 * else is rejected. The gateway (not the ML sidecar) owns this, so the model can suggest
 * commands but never trigger execution on its own.
 */
@RestController
@RequestMapping("/api/workflow")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class WorkflowController {

    private final AuditService audit;

    // Read-only diagnostics only. Deliberately excludes anything that mutates state.
    private static final List<String> SAFE_PREFIXES = List.of(
            "systemctl status", "df -h", "free -m", "uptime", "ps aux",
            "netstat -tlnp", "ss -tlnp", "docker ps", "nginx -t"
    );

    public WorkflowController(AuditService audit) {
        this.audit = audit;
    }

    public record CommandRequest(@NotBlank String command) {
    }

    @PostMapping("/execute-command")
    public Map<String, Object> execute(@AuthenticationPrincipal AuthPrincipal principal,
                                       @RequestBody CommandRequest req, HttpServletRequest http) {
        String command = req.command().trim();
        boolean allowed = SAFE_PREFIXES.stream().anyMatch(command::startsWith);

        // Always audit the attempt — allowed or blocked.
        audit.record(principal.orgId(), principal.userId(), principal.email(),
                allowed ? "command_executed" : "command_blocked", "command", command, clientIp(http));

        if (!allowed) {
            throw ApiException.badRequest("Command not in the safe allowlist");
        }

        // NOTE: actual host execution is intentionally NOT performed from the gateway
        // container (it has no host access and shouldn't). In a real deployment this would
        // dispatch to a dedicated, sandboxed runner agent with its own authz. Here we
        // acknowledge the allowlisted command and record it.
        return Map.of(
                "command", command,
                "status", "accepted",
                "note", "Allowlisted command accepted and audited. Execution is delegated to a sandboxed runner in production."
        );
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank()) ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }
}
