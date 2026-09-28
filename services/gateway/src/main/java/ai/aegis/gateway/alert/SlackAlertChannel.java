package ai.aegis.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Slack incoming-webhook channel. Active only when SLACK_WEBHOOK_URL is set (config
 * from env, not committed). Off by default.
 */
@Component
public class SlackAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackAlertChannel.class);

    private final String webhookUrl;
    private final WebClient webClient = WebClient.builder().build();

    public SlackAlertChannel(@Value("${SLACK_WEBHOOK_URL:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public String name() {
        return "slack";
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
            String text = "*[" + severity + "] " + subject + "*\n" + body;
            webClient.post()
                    .uri(webhookUrl)
                    .bodyValue(Map.of("text", text))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("Slack alert failed: {}", e.getMessage());
            return false;
        }
    }
}
