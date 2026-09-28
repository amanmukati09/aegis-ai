package ai.aegis.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/** Microsoft Teams incoming-webhook channel. Active when TEAMS_WEBHOOK_URL is set. */
@Component
public class TeamsAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(TeamsAlertChannel.class);
    private final String webhookUrl;
    private final WebClient webClient = WebClient.builder().build();

    public TeamsAlertChannel(@Value("${TEAMS_WEBHOOK_URL:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public String name() {
        return "teams";
    }

    @Override
    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    @Override
    public boolean send(String subject, String body, String severity) {
        if (!isConfigured()) {
            return false;
        }
        try {
            webClient.post().uri(webhookUrl)
                    .bodyValue(Map.of("text", "**[" + severity + "] " + subject + "**\n\n" + body))
                    .retrieve().toBodilessEntity().timeout(Duration.ofSeconds(10)).block();
            return true;
        } catch (Exception e) {
            log.warn("Teams alert failed: {}", e.getMessage());
            return false;
        }
    }
}
