package ai.aegis.gateway.ml;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.ml.MlDtos.ChatRequest;
import ai.aegis.gateway.ml.MlDtos.ChatResponse;
import ai.aegis.gateway.ml.MlDtos.DetectRequest;
import ai.aegis.gateway.ml.MlDtos.DetectResponse;
import ai.aegis.gateway.ml.MlDtos.DiagnoseRequest;
import ai.aegis.gateway.ml.MlDtos.DiagnoseResponse;
import ai.aegis.gateway.ml.MlDtos.RemediationRequest;
import ai.aegis.gateway.ml.MlDtos.RemediationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Calls the FastAPI ML sidecar over HTTP. Synchronous (block with timeout) since the
 * gateway pipeline is short and sequential; runs on virtual threads so blocking is cheap.
 */
@Component
public class MlClient {

    private static final Logger log = LoggerFactory.getLogger(MlClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient webClient;

    public MlClient(WebClient mlServiceWebClient) {
        this.webClient = mlServiceWebClient;
    }

    public ChatResponse chat(ChatRequest req) {
        return post("/v1/chat", req, ChatResponse.class);
    }

    /** Stream chat tokens from the ML sidecar as a reactive flux of SSE data chunks. */
    public reactor.core.publisher.Flux<String> chatStream(ChatRequest req) {
        return webClient.post()
                .uri("/v1/chat/stream")
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(String.class);
    }

    /** Proxy the ML model catalogue. */
    public java.util.Map<String, Object> models() {
        return webClient.get()
                .uri("/v1/models")
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>() {})
                .timeout(TIMEOUT)
                .block();
    }

    public DetectResponse detect(DetectRequest req) {
        return post("/v1/detect-anomaly", req, DetectResponse.class);
    }

    public DiagnoseResponse diagnose(DiagnoseRequest req) {
        return post("/v1/diagnose", req, DiagnoseResponse.class);
    }

    public RemediationResponse suggestRemediation(RemediationRequest req) {
        return post("/v1/remediation/suggest", req, RemediationResponse.class);
    }

    /** Advanced endpoints return free-form JSON; the gateway passes them through. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> rcaTree(Object req) {
        return post("/v1/rca-tree", req, java.util.Map.class);
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> codeFix(Object req) {
        return post("/v1/code-fix", req, java.util.Map.class);
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> nlToSql(Object req) {
        return post("/v1/nl-to-sql", req, java.util.Map.class);
    }

    private <T> T post(String path, Object body, Class<T> type) {
        try {
            return webClient.post()
                    .uri(path)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(type)
                    .timeout(TIMEOUT)
                    .block();
        } catch (Exception e) {
            log.error("ML call to {} failed: {}", path, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI service unavailable: " + e.getMessage());
        }
    }
}
