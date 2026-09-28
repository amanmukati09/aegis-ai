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
    private final ai.aegis.gateway.alert.AlertService alerts;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public AdminController(UserRepository users, AuditLogRepository auditLogs, IncidentRepository incidents,
                           ai.aegis.gateway.alert.AlertService alerts,
                           org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.users = users;
        this.auditLogs = auditLogs;
        this.incidents = incidents;
        this.alerts = alerts;
        this.passwordEncoder = passwordEncoder;
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

    @org.springframework.web.bind.annotation.GetMapping("/alerts/status")
    public Map<String, Object> alertStatus() {
        return Map.of("configuredChannels", alerts.configuredChannels());
    }

    @org.springframework.web.bind.annotation.PostMapping("/alerts/test")
    public Map<String, Object> alertTest() {
        alerts.dispatch("Test alert", "This is a test alert from AegisAI.", "critical", "low");
        return Map.of("status", "sent", "channels", alerts.configuredChannels());
    }

    // ---- Invite a member into the caller's org ----
    public record InviteRequest(@jakarta.validation.constraints.Email @jakarta.validation.constraints.NotBlank String email,
                                @jakarta.validation.constraints.NotBlank String fullName,
                                String role, @jakarta.validation.constraints.NotBlank String tempPassword) {
    }

    @org.springframework.web.bind.annotation.PostMapping("/users/invite")
    @org.springframework.transaction.annotation.Transactional
    public UserView invite(@AuthenticationPrincipal AuthPrincipal principal,
                           @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody InviteRequest req) {
        String email = req.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw ai.aegis.gateway.common.ApiException.conflict("Email already registered");
        }
        // Members and org_admins can be invited; only super_admin could create another super_admin (not exposed).
        ai.aegis.gateway.user.Role role = "org_admin".equalsIgnoreCase(req.role())
                ? ai.aegis.gateway.user.Role.ORG_ADMIN : ai.aegis.gateway.user.Role.MEMBER;
        ai.aegis.gateway.user.User u = new ai.aegis.gateway.user.User(
                java.util.UUID.randomUUID(), principal.orgId(), email,
                passwordEncoder.encode(req.tempPassword()), req.fullName().trim(), role);
        users.save(u);
        return new UserView(u.getId().toString(), u.getEmail(), u.getFullName(), u.getRole().toDb(),
                u.isActive(), u.getLastLoginAt());
    }
}
