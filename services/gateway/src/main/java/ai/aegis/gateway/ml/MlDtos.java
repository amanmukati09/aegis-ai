package ai.aegis.gateway.ml;

import java.util.List;
import java.util.Map;

/** DTOs mirroring the ML sidecar's /v1 contract. */
public final class MlDtos {

    private MlDtos() {
    }

    // ---- chat ----
    // toolToken: short-lived JWT (JwtService.issueToolToken) scoped to the requesting
    // user, forwarded so the ML sidecar's agent tools can call back into the gateway's
    // own REST API (incident/KB search, NL-to-SQL) and have those calls authenticate as
    // the same user — workspace-visibility and org scoping then apply automatically,
    // with no new auth mechanism. gatewayBaseUrl tells the sidecar where to call back to.
    public record ChatRequest(String message, List<Map<String, String>> history,
                              String system, String provider, String model,
                              String toolToken, String gatewayBaseUrl) {

        /** Convenience constructor for callers that don't need tool-calling context. */
        public ChatRequest(String message, List<Map<String, String>> history,
                          String system, String provider, String model) {
            this(message, history, system, provider, model, null, null);
        }
    }

    public record ChatResponse(String reply, String model, String provider) {
    }

    // ---- detect ----
    public record DetectRequest(List<String> logs, String provider, String model) {
    }

    public record DetectResponse(boolean anomaly_detected, String anomaly_type, String severity,
                                 String affected_component, String description) {
    }

    // ---- diagnose ----
    public record DiagnoseRequest(Map<String, Object> anomaly, List<String> logs,
                                  String provider, String model) {
    }

    public record DiagnoseResponse(String root_cause, double confidence,
                                   List<String> evidence, List<String> contributing_factors) {
    }

    // ---- remediation ----
    public record RemediationRequest(Map<String, Object> anomaly, Map<String, Object> root_cause,
                                     String provider, String model) {
    }

    public record RemediationResponse(List<String> immediate_actions, List<String> diagnostic_commands,
                                      boolean escalation_needed, String estimated_recovery_time,
                                      List<String> prevention_measures) {
    }
}
