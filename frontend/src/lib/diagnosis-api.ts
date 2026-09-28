import { apiPost } from "./api";

export type DiagnoseResult = {
  incidentId: string;
  anomaly: {
    anomaly_detected: boolean;
    anomaly_type: string;
    severity: string;
    affected_component: string;
    description: string;
  };
  diagnosis: {
    root_cause: string;
    confidence: number;
    evidence: string[];
    contributing_factors: string[];
  };
  remediation: {
    immediate_actions: string[];
    diagnostic_commands: string[];
    escalation_needed: boolean;
    estimated_recovery_time: string;
    prevention_measures: string[];
  };
};

export function diagnose(token: string, logs: string[], provider?: string, model?: string) {
  return apiPost<DiagnoseResult>("/diagnose", { logs, provider, model }, token);
}
