package ai.aegis.gateway.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lightweight app-level health/info endpoint (in addition to actuator).
 * Used by the frontend health page to confirm the gateway is reachable.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", "aegisai-gateway",
                "status", "ok",
                "version", "0.1.0"
        );
    }
}
