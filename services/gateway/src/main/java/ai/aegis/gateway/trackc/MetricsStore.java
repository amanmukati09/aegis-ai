package ai.aegis.gateway.trackc;

/**
 * Track C port: a time-series metrics store (e.g. TimescaleDB). Dormant by default.
 *
 * The core stores incidents in Postgres. Track C adds a dedicated time-series store for
 * high-cardinality metrics + Grafana dashboards. Activate on a capable host by starting
 * the metrics overlay and setting SPRING_PROFILES_ACTIVE=...,timescale — see
 * LAPTOP_SETUP.md. Until then the {@link DormantMetricsStore} is wired and reports inactive.
 */
public interface MetricsStore {

    String name();

    boolean isActive();

    String status();
}
