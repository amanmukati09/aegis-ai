package ai.aegis.gateway.admin;

import ai.aegis.gateway.audit.AuditLog;
import ai.aegis.gateway.audit.AuditLogRepository;
import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.security.AuthPrincipal;
import ai.aegis.gateway.user.User;
import ai.aegis.gateway.user.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin panel endpoints — org_admin sees their org; super_admin sees platform-wide.
 * All gated at method level.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
public class AdminController {

    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final IncidentRepository incidents;

    public AdminController(UserRepository users, AuditLogRepository auditLogs, IncidentRepository incidents) {
        this.users = users;
        this.auditLogs = auditLogs;
        this.incidents = incidents;
    }

    public record UserView(String id, String email, String fullName, String role,
                           boolean active, OffsetDateTime lastLoginAt) {
    }

    public record AuditView(String id, String action, String userEmail, String resourceType,
                            String resourceId, String ipAddress, OffsetDateTime createdAt) {
    }

    @GetMapping("/users")
    public List<UserView> orgUsers(@AuthenticationPrincipal AuthPrincipal principal) {
        List<User> list = principal.isSuperAdmin() ? users.findAll() : users.findByOrgId(principal.orgId());
        return list.stream()
                .map(u -> new UserView(u.getId().toString(), u.getEmail(), u.getFullName(),
                        u.getRole().toDb(), u.isActive(), u.getLastLoginAt()))
                .toList();
    }

    @GetMapping("/audit-logs")
    public List<AuditView> auditLogs(@AuthenticationPrincipal AuthPrincipal principal) {
        List<AuditLog> logs = auditLogs.findTop100ByOrgIdOrderByCreatedAtDesc(principal.orgId());
        return logs.stream()
                .map(a -> new AuditView(a.getId().toString(), a.getAction(), a.getUserEmail(),
                        a.getResourceType(), a.getResourceId(), a.getIpAddress(), a.getCreatedAt()))
                .toList();
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@AuthenticationPrincipal AuthPrincipal principal) {
        return Map.of(
                "users", users.countByOrgId(principal.orgId()),
                "incidents", incidents.countByOrgId(principal.orgId())
        );
    }
}
