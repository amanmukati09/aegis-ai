package ai.aegis.gateway.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Notifications for a user. create() is reused by other modules (incidents, etc.)
 * to notify users of events.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void create(UUID userId, String type, String title, String message) {
        repository.save(new Notification(UUID.randomUUID(), userId, type, title, message));
    }
}
