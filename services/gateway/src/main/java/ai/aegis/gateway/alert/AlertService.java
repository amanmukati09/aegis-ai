package ai.aegis.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Dispatches alerts to every configured channel. Channels are injected as a list of
 * AlertChannel beans, so adding an integration requires no change here (open/closed).
 * Severity gating: only fire at or above the configured minimum severity.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    // Higher number = more severe.
    private static final Map<String, Integer> RANK = Map.of(
            "low", 1, "medium", 2, "high", 3, "critical", 4
    );

    private final List<AlertChannel> channels;

    public AlertService(List<AlertChannel> channels) {
        this.channels = channels;
    }

    /** Fire an alert if severity >= minSeverity, to all configured channels. */
    public void dispatch(String subject, String body, String severity, String minSeverity) {
        if (rank(severity) < rank(minSeverity)) {
            return;
        }
        for (AlertChannel channel : channels) {
            if (channel.isConfigured()) {
                boolean ok = channel.send(subject, body, severity);
                if (!ok) {
                    log.warn("Alert channel {} failed to send", channel.name());
                }
            }
        }
    }

    /** Convenience: fire for high/critical incidents. */
    public void dispatchIncident(String title, String severity) {
        dispatch("Incident: " + title, "Severity " + severity + " incident detected.", severity, "high");
    }

    public List<String> configuredChannels() {
        return channels.stream().filter(AlertChannel::isConfigured).map(AlertChannel::name).toList();
    }

    private int rank(String severity) {
        return RANK.getOrDefault(severity == null ? "medium" : severity.toLowerCase(), 2);
    }
}
