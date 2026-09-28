import { apiDelete, apiGet, apiPost } from "./api";

export type Incident = {
  id: string;
  orgId: string;
  userId: string | null;
  assignedTo: string | null;
  title: string;
  status: string;
  severity: string | null;
  anomalyDescription: string | null;
  rootCause: string | null;
  remediationAction: string | null;
  remediationStatus: string | null;
  resolutionNotes: string | null;
  detectedAt: string | null;
  resolvedAt: string | null;
};

export type IncidentPage = {
  items: Incident[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type DashboardSummary = {
  total: number;
  open: number;
  resolved: number;
  mttrHours: number | null;
  byStatus: Record<string, number>;
  bySeverity: Record<string, number>;
  recent: Incident[];
};

export function listIncidents(token: string, page = 0, size = 20) {
  return apiGet<IncidentPage>(`/incidents?page=${page}&size=${size}`, token);
}

export function getIncident(token: string, id: string) {
  return apiGet<Incident>(`/incidents/${id}`, token);
}

export function createIncident(
  token: string,
  input: { title: string; severity?: string; rawLogs?: string; anomalyDescription?: string }
) {
  return apiPost<Incident>("/incidents", input, token);
}

export function resolveIncident(token: string, id: string, resolutionNotes?: string) {
  return apiPost<Incident>(`/incidents/${id}/resolve`, { resolutionNotes }, token);
}

export function deleteIncident(token: string, id: string) {
  return apiDelete<void>(`/incidents/${id}`, token);
}

export function claimIncident(token: string, id: string) {
  return apiPost<Incident>(`/incidents/${id}/claim`, {}, token);
}

// Advanced ML on an incident (free-form JSON results).
export function incidentRcaTree(token: string, id: string) {
  return apiPost<Record<string, unknown>>(`/incidents/${id}/rca-tree`, {}, token);
}

export function incidentCodeFix(token: string, id: string) {
  return apiPost<Record<string, unknown>>(`/incidents/${id}/code-fix`, {}, token);
}

export function incidentRunbook(token: string, id: string) {
  return apiPost<Record<string, unknown>>(`/incidents/${id}/runbook`, {}, token);
}

export type SimilarIncident = { id: string; title: string; severity: string; status: string; score: number };
export function incidentSimilar(token: string, id: string) {
  return apiGet<SimilarIncident[]>(`/incidents/${id}/similar`, token);
}

export type TimelineEvent = { type: string; label: string; at?: string; detail?: string };
export function incidentTimeline(token: string, id: string) {
  return apiGet<TimelineEvent[]>(`/incidents/${id}/timeline`, token);
}

export async function downloadIncidentPdf(token: string, id: string) {
  const res = await fetch(`/api/gateway/incidents/${id}/report.pdf`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`PDF failed (HTTP ${res.status})`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `incident-${id.slice(0, 8)}.pdf`;
  a.click();
  URL.revokeObjectURL(url);
}

export function getDashboardSummary(token: string) {
  return apiGet<DashboardSummary>("/dashboard/summary", token);
}

// CSV download: fetch the blob through the proxy (with auth), then trigger a save.
export async function downloadIncidentsCsv(token: string) {
  const res = await fetch("/api/gateway/incidents/export/csv", {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`Export failed (HTTP ${res.status})`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "incidents.csv";
  a.click();
  URL.revokeObjectURL(url);
}
