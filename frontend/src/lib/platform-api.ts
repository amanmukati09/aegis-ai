import { apiDelete, apiGet, apiPost } from "./api";

// ---- Streams ----
export type Stream = {
  id: string;
  name: string;
  description: string | null;
  sourceType: string;
  status: string;
  createdAt: string;
};
export const streamsApi = {
  list: (t: string) => apiGet<Stream[]>("/streams", t),
  create: (t: string, name: string, description?: string, sourceType?: string) =>
    apiPost<Stream>("/streams", { name, description, sourceType }, t),
  remove: (t: string, id: string) => apiDelete<void>(`/streams/${id}`, t),
};

// ---- Notifications ----
export type Notification = {
  id: string;
  type: string | null;
  title: string;
  message: string | null;
  read: boolean;
  createdAt: string;
};
export type NotificationsResponse = { notifications: Notification[]; unreadCount: number };
export const notificationsApi = {
  list: (t: string) => apiGet<NotificationsResponse>("/notifications", t),
  markRead: (t: string) => apiPost<{ status: string }>("/notifications/mark-read", {}, t),
};

// ---- API keys ----
export type ApiKeyView = {
  id: string;
  name: string;
  keyPrefix: string;
  active: boolean;
  lastUsedAt: string | null;
  expiresAt: string | null;
  createdAt: string;
};
export type CreatedKey = { id: string; name: string; key: string; keyPrefix: string };
export const apiKeysApi = {
  list: (t: string) => apiGet<ApiKeyView[]>("/api-keys", t),
  create: (t: string, name: string, expiresInDays?: number) =>
    apiPost<CreatedKey>("/api-keys", { name, expiresInDays }, t),
  revoke: (t: string, id: string) => apiDelete<void>(`/api-keys/${id}`, t),
};

// ---- Workspaces ----
export type Workspace = { id: string; name: string; description: string | null; createdAt: string };
export type WorkspaceMember = { id: string; userId: string; role: string };
export const workspacesApi = {
  list: (t: string) => apiGet<Workspace[]>("/workspaces", t),
  create: (t: string, name: string, description?: string) =>
    apiPost<Workspace>("/workspaces", { name, description }, t),
  remove: (t: string, id: string) => apiDelete<void>(`/workspaces/${id}`, t),
  members: (t: string, id: string) => apiGet<WorkspaceMember[]>(`/workspaces/${id}/members`, t),
  addMember: (t: string, id: string, userId: string, role?: string) =>
    apiPost<WorkspaceMember>(`/workspaces/${id}/members`, { userId, role }, t),
};

// ---- Admin ----
export type AdminUser = {
  id: string;
  email: string;
  fullName: string;
  role: string;
  active: boolean;
  lastLoginAt: string | null;
};
export type AuditEntry = {
  id: string;
  action: string;
  userEmail: string | null;
  resourceType: string | null;
  resourceId: string | null;
  ipAddress: string | null;
  createdAt: string;
};
export const adminApi = {
  users: (t: string) => apiGet<AdminUser[]>("/admin/users", t),
  auditLogs: (t: string) => apiGet<AuditEntry[]>("/admin/audit-logs", t),
  metrics: (t: string) => apiGet<Record<string, number>>("/admin/metrics", t),
  alertStatus: (t: string) => apiGet<{ configuredChannels: string[] }>("/admin/alerts/status", t),
  alertTest: (t: string) => apiPost<{ status: string; channels: string[] }>("/admin/alerts/test", {}, t),
  invite: (t: string, input: { email: string; fullName: string; role: string; tempPassword: string }) =>
    apiPost<AdminUser>("/admin/users/invite", input, t),
};

// ---- Analytics (NL->SQL + charts) ----
export type AskResult = {
  sql: string;
  explanation: string;
  chartType: string;
  columns: string[];
  rows: Record<string, unknown>[];
};
export type TimeseriesPoint = { day: string; total: number };
export const analyticsApi = {
  presets: (t: string) => apiGet<string[]>("/analytics/presets", t),
  ask: (t: string, question: string) => apiPost<AskResult>("/analytics/ask", { question }, t),
  timeseries: (t: string) => apiGet<TimeseriesPoint[]>("/analytics/timeseries", t),
};

// ---- Dependency graph ----
export type GraphNode = { id: string; label: string; weight: number };
export type GraphEdge = { source: string; target: string; weight: number };
export type DependencyGraph = { nodes: GraphNode[]; edges: GraphEdge[]; hasData: boolean };
export type BlastRadius = {
  component: string;
  directImpact: string[];
  indirectImpact: string[];
  radius: number;
};
export const dependencyApi = {
  graph: (t: string) => apiGet<DependencyGraph>("/dependency/graph", t),
  blastRadius: (t: string, component: string) =>
    apiGet<BlastRadius>(`/dependency/blast-radius/${encodeURIComponent(component)}`, t),
};

// ---- Async jobs (bulk analysis) ----
export type Job = {
  id: string;
  type: string;
  status: string;
  result: Record<string, unknown> | null;
  error: string | null;
};
export const jobsApi = {
  bulkAnalyze: (t: string, logs: string[]) => apiPost<Job>("/jobs/bulk-analyze", { logs }, t),
  get: (t: string, id: string) => apiGet<Job>(`/jobs/${id}`, t),
};

// ---- Ingest from URL ----
export const ingestApi = {
  fromUrl: (t: string, url: string) =>
    apiPost<{ lines: string[]; count: number; source: string }>("/ingest/from-url", { url }, t),
};

// ---- Track C capabilities (dormant/extended) ----
export type TrackCCapability = {
  key: string;
  label: string;
  adapter: string;
  active: boolean;
  status: string;
};
export type TrackCStatus = { mode: string; capabilities: TrackCCapability[] };
export const trackcApi = {
  status: (t: string) => apiGet<TrackCStatus>("/trackc/status", t),
};

// ---- Insights (analytics suite) ----
export type HealthScore = {
  score: number;
  status: string;
  totalIncidents: number;
  openIncidents: number;
  resolvedIncidents: number;
  critical1h: number;
  incidentVelocity: number;
  topRiskComponent: string;
  resolutionRate: number;
};
export type Benchmark = {
  totalIncidents: number;
  diagnosisAccuracy: number;
  remediationRate: number;
  resolutionRate: number;
  avgResolutionHours: number;
  recent7d: number;
  withRootCause: number;
  withRemediation: number;
};
export type Prediction = { type: string; title: string; detail: string; confidence: number };
export type Predictions = {
  predictions: Prediction[];
  riskLevel: string;
  totalIncidents: number;
  summary: string;
};
export type IncidentCluster = {
  name: string;
  count: number;
  commonFeatures: string[];
  severityDistribution: Record<string, number>;
  incidentIds: string[];
};
export type Clusters = {
  clusters: IncidentCluster[];
  totalIncidents: number;
  totalClusters: number;
  summary: string;
};
export const insightsApi = {
  healthScore: (t: string) => apiGet<HealthScore>("/insights/health-score", t),
  benchmark: (t: string) => apiGet<Benchmark>("/insights/benchmark", t),
  predictions: (t: string) => apiGet<Predictions>("/insights/predictions", t),
  clusters: (t: string) => apiGet<Clusters>("/insights/clusters", t),
};

// ---- RL triage ----
export type TriageItem = {
  incident_id: string;
  title: string;
  severity: string;
  status: string;
  component?: string;
  priority: number;
  policy: string;
  confidence: number;
  q_values: number[];
  state: number[];
};
export const triageApi = {
  queue: (t: string) => apiGet<TriageItem[]>("/triage/queue", t),
  train: (t: string) => apiPost<{ trained_on: number; q_states: number }>("/triage/train", {}, t),
};

// ---- Live monitor ----
export type LiveState = {
  timestamp: string;
  total: number;
  open: number;
  recent: { id: string; title: string; severity: string; status: string; detectedAt: string }[];
};
export const liveApi = {
  state: (t: string) => apiGet<LiveState>("/live/state", t),
};
