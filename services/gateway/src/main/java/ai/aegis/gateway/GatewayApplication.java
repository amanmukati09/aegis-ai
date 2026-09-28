package ai.aegis.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AegisAI gateway entry point.
 *
 * <p>The transactional core and orchestration layer. Owns persistence (PostgreSQL),
 * authentication/authorization, and calls the FastAPI ML sidecar over HTTP for
 * inference/generation.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
