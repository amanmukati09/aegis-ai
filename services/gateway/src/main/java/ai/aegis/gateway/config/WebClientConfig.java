package ai.aegis.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient used to call the FastAPI ML sidecar asynchronously.
 * The base URL comes from ML_SERVICE_URL so the sidecar location is config-driven.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient mlServiceWebClient(@Value("${ml.service.url:http://ml-service:8001}") String mlServiceUrl) {
        return WebClient.builder()
                .baseUrl(mlServiceUrl)
                .build();
    }
}
