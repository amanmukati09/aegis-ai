package ai.aegis.gateway.notification;

import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository repository;

    public NotificationController(NotificationRepository repository) {
        this.repository = repository;
    }

    public record NotificationView(String id, String type, String title, String message,
                                   boolean read, OffsetDateTime createdAt) {
    }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthPrincipal principal) {
        List<NotificationView> items = repository
                .findTop50ByUserIdOrderByCreatedAtDesc(principal.userId())
                .stream()
                .map(n -> new NotificationView(n.getId().toString(), n.getType(), n.getTitle(),
                        n.getMessage(), n.isRead(), n.getCreatedAt()))
                .toList();
        long unread = repository.countByUserIdAndReadFalse(principal.userId());
        return Map.of("notifications", items, "unreadCount", unread);
    }

    @PostMapping("/mark-read")
    @Transactional
    public Map<String, String> markRead(@AuthenticationPrincipal AuthPrincipal principal) {
        repository.markAllRead(principal.userId());
        return Map.of("status", "ok");
    }
}
