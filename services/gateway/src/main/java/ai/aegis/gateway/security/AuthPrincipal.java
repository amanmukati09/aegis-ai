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

    /** Org admins administer their whole org, so they bypass workspace-visibility limits. */
    public boolean isOrgAdmin() {
        return role == Role.ORG_ADMIN;
    }

    /** Super admin or org admin — sees every incident in scope regardless of workspace. */
    public boolean bypassesWorkspaceVisibility() {
        return isSuperAdmin() || isOrgAdmin();
    }
}
