package ai.aegis.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/** Opsgenie Alerts API. Active when OPSGENIE_API_KEY is set. */
@Component
public class OpsgenieAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(OpsgenieAlertChannel.class);
    private static final String ALERTS = "https://api.opsgenie.com/v2/alerts";

    private final String apiKey;
    private final WebClient webClient = WebClient.builder().build();

    public OpsgenieAlertChannel(@Value("${OPSGENIE_API_KEY:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return "opsgenie";
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public boolean send(String subject, String body, String severity) {
        if (!isConfigured()) {
            return false;
        }
        try {
            webClient.post().uri(ALERTS)
                    .header("Authorization", "GenieKey " + apiKey)
                    .bodyValue(Map.of("message", "[" + severity + "] " + subject, "description", body))
                    .retrieve().toBodilessEntity().timeout(Duration.ofSeconds(10)).block();
            return true;
        } catch (Exception e) {
            log.warn("Opsgenie alert failed: {}", e.getMessage());
            return false;
        }
    }
}
