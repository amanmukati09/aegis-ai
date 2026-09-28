package ai.aegis.gateway;

import ai.aegis.gateway.common.HealthController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 smoke test: no DB/Spring context required, verifies the health payload.
 * A full @SpringBootTest context-load test is added once the datasource is wired
 * in CI (it needs Postgres, provided by Testcontainers in Phase 1).
 */
class HealthControllerTest {

    @Test
    void healthReturnsOk() {
        HealthController controller = new HealthController();
        var body = controller.health();
        assertThat(body.get("status")).isEqualTo("ok");
        assertThat(body.get("service")).isEqualTo("aegisai-gateway");
    }
}
