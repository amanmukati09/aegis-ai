package ai.aegis.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/** PagerDuty Events API v2. Active when PAGERDUTY_ROUTING_KEY is set. */
@Component
public class PagerDutyAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(PagerDutyAlertChannel.class);
    private static final String ENQUEUE = "https://events.pagerduty.com/v2/enqueue";

    private final String routingKey;
    private final WebClient webClient = WebClient.builder().build();

    public PagerDutyAlertChannel(@Value("${PAGERDUTY_ROUTING_KEY:}") String routingKey) {
        this.routingKey = routingKey;
    }

    @Override
    public String name() {
        return "pagerduty";
    }

    @Override
    public boolean isConfigured() {
        return routingKey != null && !routingKey.isBlank();
    }

    @Override
    public boolean send(String subject, String body, String severity) {
        if (!isConfigured()) {
            return false;
        }
        try {
            // Map our severity to PagerDuty severity levels.
            String pdSeverity = switch (severity == null ? "" : severity.toLowerCase()) {
                case "critical" -> "critical";
                case "high" -> "error";
                case "medium" -> "warning";
                default -> "info";
            };
            Map<String, Object> payload = Map.of(
                    "routing_key", routingKey,
                    "event_action", "trigger",
                    "payload", Map.of(
                            "summary", subject,
                            "source", "aegisai",
                            "severity", pdSeverity,
                            "custom_details", Map.of("body", body))
            );
            webClient.post().uri(ENQUEUE).bodyValue(payload)
                    .retrieve().toBodilessEntity().timeout(Duration.ofSeconds(10)).block();
            return true;
        } catch (Exception e) {
            log.warn("PagerDuty alert failed: {}", e.getMessage());
            return false;
        }
    }
}
