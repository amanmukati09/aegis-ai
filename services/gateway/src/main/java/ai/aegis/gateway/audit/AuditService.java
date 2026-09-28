package ai.aegis.gateway.audit;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(UUID orgId, UUID userId, String userEmail, String action,
                       String resourceType, String resourceId, String ipAddress) {
        repository.save(new AuditLog(UUID.randomUUID(), orgId, userId, userEmail, action,
                resourceType, resourceId, ipAddress));
    }
}
