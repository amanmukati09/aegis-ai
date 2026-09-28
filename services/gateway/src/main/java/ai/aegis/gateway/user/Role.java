package ai.aegis.gateway.user;

/**
 * Platform roles. Stored lowercase in the DB (super_admin/org_admin/member);
 * Spring authorities use the ROLE_ prefix (ROLE_SUPER_ADMIN, ...).
 */
public enum Role {
    SUPER_ADMIN,
    ORG_ADMIN,
    MEMBER;

    public static Role fromDb(String value) {
        return Role.valueOf(value.toUpperCase());
    }

    public String toDb() {
        return name().toLowerCase();
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
