package ai.aegis.gateway.trackc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default (dormant) Track C adapters.
 *
 * Each port gets a no-op implementation that is wired only when no real adapter bean is
 * present ({@link ConditionalOnMissingBean}). This is the "dormant-ready" design: the
 * interfaces, wiring, config, status endpoint and compose overlays all ship in the repo,
 * but the heavy infrastructure (Kafka / TimescaleDB / Neo4j) stays OFF so the core runs
 * comfortably on a small host.
 *
 * To activate on a capable machine (see LAPTOP_SETUP.md):
 *   1. bring up the overlay: docker compose -f docker-compose.yml -f docker-compose.kafka.yml up -d
 *   2. add the client dependency to the gateway pom and provide a real @Bean for the port
 *      (e.g. a KafkaIngestionSource) — it will replace the dormant bean automatically.
 *   3. set the matching Spring profile + connection env in .env.
 */
@Configuration
public class DormantAdapters {

    private static final Logger log = LoggerFactory.getLogger(DormantAdapters.class);

    @Bean
    @ConditionalOnMissingBean(IngestionSource.class)
    public IngestionSource dormantIngestionSource() {
        log.info("Track C: ingestion source DORMANT (HTTP ingest only). Activate Kafka via LAPTOP_SETUP.md.");
        return new DormantIngestionSource();
    }

    @Bean
    @ConditionalOnMissingBean(MetricsStore.class)
    public MetricsStore dormantMetricsStore() {
        log.info("Track C: metrics store DORMANT (Postgres only). Activate TimescaleDB via LAPTOP_SETUP.md.");
        return new DormantMetricsStore();
    }

    @Bean
    @ConditionalOnMissingBean(GraphStore.class)
    public GraphStore dormantGraphStore() {
        log.info("Track C: graph store DORMANT (Postgres co-occurrence graph). Activate Neo4j via LAPTOP_SETUP.md.");
        return new DormantGraphStore();
    }

    static final class DormantIngestionSource implements IngestionSource {
        public String name() { return "dormant"; }
        public boolean isActive() { return false; }
        public String status() { return "HTTP ingest active; Kafka streaming source not enabled"; }
    }

    static final class DormantMetricsStore implements MetricsStore {
        public String name() { return "dormant"; }
        public boolean isActive() { return false; }
        public String status() { return "Postgres analytics active; TimescaleDB metrics store not enabled"; }
    }

    static final class DormantGraphStore implements GraphStore {
        public String name() { return "dormant"; }
        public boolean isActive() { return false; }
        public String status() { return "Postgres co-occurrence graph active; Neo4j graph store not enabled"; }
    }
}
