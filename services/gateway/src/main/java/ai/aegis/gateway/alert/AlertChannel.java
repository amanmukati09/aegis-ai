package ai.aegis.gateway.alert;

/**
 * Pluggable alert channel port. Add a new integration (PagerDuty, Opsgenie, Teams…)
 * by implementing this interface and registering it as a Spring bean — AlertService
 * discovers all beans automatically. No changes to callers.
 */
public interface AlertChannel {

    /** Stable channel identifier, e.g. "stdout", "slack", "webhook". */
    String name();

    /** True when this channel has the config it needs (keys/URLs) to send. */
    boolean isConfigured();

    /** Deliver an alert. Implementations must not throw; return false on failure. */
    boolean send(String subject, String body, String severity);
}
