package ai.aegis.gateway.trackc;

/**
 * Track C port: a streaming ingestion source (e.g. Kafka). Dormant by default.
 *
 * The core platform ingests logs over HTTP (paste / file / URL). Track C adds a
 * push-based streaming source for high-volume environments. The real Kafka adapter is
 * activated on a capable host (the laptop) by adding the Kafka client dependency and
 * setting SPRING_PROFILES_ACTIVE=...,kafka — see LAPTOP_SETUP.md. Until then the
 * {@link DormantIngestionSource} is wired and reports inactive.
 */
public interface IngestionSource {

    /** Stable identifier, e.g. "kafka", "dormant". */
    String name();

    /** True when the source is connected and consuming. */
    boolean isActive();

    /** One-line human status for the ops/status endpoint. */
    String status();
}
