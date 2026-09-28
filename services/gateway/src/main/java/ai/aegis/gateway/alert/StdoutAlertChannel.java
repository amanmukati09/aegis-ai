package ai.aegis.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Always-available demo channel: logs the alert to stdout. */
@Component
public class StdoutAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(StdoutAlertChannel.class);

    @Override
    public String name() {
        return "stdout";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public boolean send(String subject, String body, String severity) {
        log.info("[ALERT][{}] {} — {}", severity, subject, body);
        return true;
    }
}
