package ai.aegis.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;

/**
 * AegisAI gateway entry point.
 *
 * <p>The transactional core and orchestration layer. Owns persistence (PostgreSQL),
 * authentication/authorization, and calls the FastAPI ML sidecar over HTTP for
 * inference/generation.
 *
 * <p>Mail autoconfig is excluded — the SMTP alert channel builds its own sender from
 * env only when configured, so a missing SMTP host never breaks startup.
 */
@SpringBootApplication(exclude = MailSenderAutoConfiguration.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
