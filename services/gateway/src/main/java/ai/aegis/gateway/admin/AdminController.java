package ai.aegis.gateway.admin;

import ai.aegis.gateway.audit.AuditLog;
import ai.aegis.gateway.audit.AuditLogRepository;
import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.common.ApiException;
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
    private final AuditService audit;
    private final IncidentRepository incidents;
    private final ai.aegis.gateway.alert.AlertService alerts;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public AdminController(UserRepository users, AuditLogRepository auditLogs, AuditService audit,
                           IncidentRepository incidents, ai.aegis.gateway.alert.AlertService alerts,
                           org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.users = users;
        this.auditLogs = auditLogs;
        this.audit = audit;
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
            throw ApiException.conflict("Email already registered");
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

    // ---- User lifecycle: status, role, delete, password reset ----

    private static final List<String> ADMIN_ROLES = List.of("super_admin", "org_admin");

    /** Resolve a target user, enforcing org boundary for org_admin callers. */
    private User requireManageableUser(AuthPrincipal principal, java.util.UUID id) {
        User target = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        if (!principal.isSuperAdmin() && !java.util.Objects.equals(target.getOrgId(), principal.orgId())) {
            throw ApiException.notFound("User not found");
        }
        return target;
    }

    public record StatusRequest(boolean active) {
    }

    /** Activate/deactivate a user. A deactivated user can no longer log in or use API keys. */
    @org.springframework.web.bind.annotation.PutMapping("/users/{id}/status")
    @org.springframework.transaction.annotation.Transactional
    public UserView setStatus(@AuthenticationPrincipal AuthPrincipal principal,
                              @org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
                              @org.springframework.web.bind.annotation.RequestBody StatusRequest req,
                              jakarta.servlet.http.HttpServletRequest http) {
        User target = requireManageableUser(principal, id);
        if (target.getId().equals(principal.userId()) && !req.active()) {
            throw ApiException.badRequest("You cannot deactivate your own account");
        }
        if (!req.active() && isLastActiveAdmin(target)) {
            throw ApiException.badRequest("Cannot deactivate the last active admin in this organization");
        }
        target.setActive(req.active());
        users.save(target);
        audit.record(principal.orgId(), principal.userId(), principal.email(),
                req.active() ? "user_reactivated" : "user_deactivated", "user", target.getId().toString(), clientIp(http));
        return toView(target);
    }

    public record RoleRequest(@jakarta.validation.constraints.NotBlank String role) {
    }

    /** Change a user's role within their org. Only super_admin may grant/revoke super_admin. */
    @org.springframework.web.bind.annotation.PutMapping("/users/{id}/role")
    @org.springframework.transaction.annotation.Transactional
    public UserView setRole(@AuthenticationPrincipal AuthPrincipal principal,
                            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
                            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody RoleRequest req,
                            jakarta.servlet.http.HttpServletRequest http) {
        User target = requireManageableUser(principal, id);
        ai.aegis.gateway.user.Role newRole;
        try {
            newRole = ai.aegis.gateway.user.Role.fromDb(req.role().trim().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown role: " + req.role());
        }
        if (newRole == ai.aegis.gateway.user.Role.SUPER_ADMIN && !principal.isSuperAdmin()) {
            throw ApiException.forbidden("Only a super_admin can grant super_admin");
        }
        if (target.getId().equals(principal.userId()) && newRole != ai.aegis.gateway.user.Role.SUPER_ADMIN
                && isLastActiveAdmin(target)) {
            throw ApiException.badRequest("Cannot demote the last active admin in this organization");
        }
        target.setRole(newRole);
        users.save(target);
        audit.record(principal.orgId(), principal.userId(), principal.email(),
                "user_role_changed", "user", target.getId().toString(), clientIp(http));
        return toView(target);
    }

    public record ResetPasswordResponse(String tempPassword) {
    }

    /** Admin-initiated password reset: generates a temp password the admin relays to the user. */
    @org.springframework.web.bind.annotation.PostMapping("/users/{id}/reset-password")
    @org.springframework.transaction.annotation.Transactional
    public ResetPasswordResponse resetPassword(@AuthenticationPrincipal AuthPrincipal principal,
                                               @org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
                                               jakarta.servlet.http.HttpServletRequest http) {
        User target = requireManageableUser(principal, id);
        String tempPassword = generateTempPassword();
        target.setPasswordHash(passwordEncoder.encode(tempPassword));
        users.save(target);
        audit.record(principal.orgId(), principal.userId(), principal.email(),
                "user_password_reset", "user", target.getId().toString(), clientIp(http));
        return new ResetPasswordResponse(tempPassword);
    }

    /** Delete a user outright. Guarded: cannot delete yourself or the last active admin. */
    @org.springframework.web.bind.annotation.DeleteMapping("/users/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @org.springframework.transaction.annotation.Transactional
    public void deleteUser(@AuthenticationPrincipal AuthPrincipal principal,
                          @org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
                          jakarta.servlet.http.HttpServletRequest http) {
        User target = requireManageableUser(principal, id);
        if (target.getId().equals(principal.userId())) {
            throw ApiException.badRequest("You cannot delete your own account");
        }
        if (isLastActiveAdmin(target)) {
            throw ApiException.badRequest("Cannot delete the last active admin in this organization");
        }
        audit.record(principal.orgId(), principal.userId(), principal.email(),
                "user_deleted", "user", target.getId() + " (" + target.getEmail() + ")", clientIp(http));
        users.delete(target);
    }

    private boolean isLastActiveAdmin(User target) {
        if (target.getOrgId() == null || !ADMIN_ROLES.contains(target.getRole().toDb())
                || !target.isActive()) {
            return false;
        }
        return users.countByOrgIdAndRoleInAndActiveTrue(target.getOrgId(), ADMIN_ROLES) <= 1;
    }

    private static String generateTempPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder("Aegis-");
        for (int i = 0; i < 10; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private UserView toView(User u) {
        return new UserView(u.getId().toString(), u.getEmail(), u.getFullName(), u.getRole().toDb(),
                u.isActive(), u.getLastLoginAt());
    }

    private String clientIp(jakarta.servlet.http.HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
