package ai.aegis.gateway.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findTop100ByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
