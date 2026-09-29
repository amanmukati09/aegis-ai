package ai.aegis.gateway.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(String role);

    java.util.List<User> findByOrgId(UUID orgId);

    Optional<User> findByIdAndOrgId(UUID id, UUID orgId);

    long countByOrgId(UUID orgId);

    /** Active admins (org_admin or super_admin) in an org — used to guard "last admin standing". */
    long countByOrgIdAndRoleInAndActiveTrue(UUID orgId, java.util.List<String> roles);
}
