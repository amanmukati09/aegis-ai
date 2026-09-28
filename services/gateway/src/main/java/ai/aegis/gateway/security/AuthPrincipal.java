package ai.aegis.gateway.security;

import ai.aegis.gateway.user.Role;

import java.util.UUID;

/**
 * Authenticated user context set on the SecurityContext. Exposed via
 * authentication.principal so @PreAuthorize can check orgId/role.
 */
public record AuthPrincipal(UUID userId, UUID orgId, String email, Role role) {

    public boolean isSuperAdmin() {
        return role == Role.SUPER_ADMIN;
    }
}
