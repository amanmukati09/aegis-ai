package ai.aegis.gateway.diagnosis;

import ai.aegis.gateway.audit.AuditService;
import ai.aegis.gateway.guardrails.GuardrailService;
import ai.aegis.gateway.incident.Incident;
import ai.aegis.gateway.incident.IncidentRepository;
import ai.aegis.gateway.ml.MlClient;
import ai.aegis.gateway.ml.MlDtos.DetectRequest;
import ai.aegis.gateway.ml.MlDtos.DetectResponse;
import ai.aegis.gateway.ml.MlDtos.DiagnoseRequest;
import ai.aegis.gateway.ml.MlDtos.DiagnoseResponse;
import ai.aegis.gateway.ml.MlDtos.RemediationRequest;
import ai.aegis.gateway.ml.MlDtos.RemediationResponse;
import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the diagnosis pipeline: mask PII -> detect anomaly -> diagnose root
 * cause -> suggest remediation (all in the ML sidecar) -> persist an Incident scoped
 * to the caller's org -> audit. The sidecar never touches the DB; the gateway owns
 * persistence.
 */
@Service
public class DiagnosisService {

    private final MlClient ml;
    private final GuardrailService guardrails;
    private final IncidentRepository incidents;
    private final AuditService audit;

    public DiagnosisService(MlClient ml, GuardrailService guardrails,
                            IncidentRepository incidents, AuditService audit) {
        this.ml = ml;
        this.guardrails = guardrails;
        this.incidents = incidents;
        this.audit = audit;
    }

    public record DiagnoseResult(
            String incidentId,
            DetectResponse anomaly,
            DiagnoseResponse diagnosis,
            RemediationResponse remediation
    ) {
    }

    @Transactional
    public DiagnoseResult run(AuthPrincipal principal, List<String> rawLogs, String provider,
                              String model, String ip) {
        // 1. Mask PII/secrets before anything leaves for the LLM.
        List<String> logs = guardrails.maskPii(rawLogs);

        // 2. Detect anomaly.
        DetectResponse detected = ml.detect(new DetectRequest(logs, provider, model));

        // 3. Diagnose root cause.
        Map<String, Object> anomalyMap = Map.of(
                "anomaly_type", nz(detected.anomaly_type()),
                "severity", nz(detected.severity()),
                "affected_component", nz(detected.affected_component()),
                "description", nz(detected.description())
        );
        DiagnoseResponse diagnosis = ml.diagnose(new DiagnoseRequest(anomalyMap, logs, provider, model));

        // 4. Suggest remediation.
        Map<String, Object> rootCauseMap = Map.of("root_cause", nz(diagnosis.root_cause()));
        RemediationResponse remediation =
                ml.suggestRemediation(new RemediationRequest(anomalyMap, rootCauseMap, provider, model));

        // 5. Persist an incident, scoped to the caller's org.
        Incident incident = new Incident(
                UUID.randomUUID(), principal.orgId(), principal.userId(),
                titleFrom(detected), detected.severity(),
                String.join("\n", logs), detected.description());
        incident.setRootCause(diagnosis.root_cause());
        incident.setRemediationStatus("pending");
        incidents.save(incident);

        audit.record(principal.orgId(), principal.userId(), principal.email(),
                "incident_diagnosed", "incident", incident.getId().toString(), ip);

        return new DiagnoseResult(incident.getId().toString(), detected, diagnosis, remediation);
    }

    private static String titleFrom(DetectResponse d) {
        String type = nz(d.anomaly_type());
        String comp = nz(d.affected_component());
        if (!type.equals("unknown") || !comp.equals("unknown")) {
            return type + " on " + comp;
        }
        return "Diagnosed incident";
    }

    private static String nz(String s) {
        return s == null ? "unknown" : s;
    }
}
