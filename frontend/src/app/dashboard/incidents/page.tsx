"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import {
  AlertTriangle, Download, FileText, GitBranch, Layers, Plus, Sparkles, Wrench, X,
} from "lucide-react";
import {
  createIncident,
  deleteIncident,
  downloadIncidentsCsv,
  incidentCodeFix,
  incidentRcaTree,
  incidentRunbook,
  incidentSimilar,
  incidentTimeline,
  downloadIncidentPdf,
  listIncidents,
  resolveIncident,
  setIncidentWorkspace,
  type Incident,
  type SimilarIncident,
  type TimelineEvent,
} from "@/lib/incidents-api";
import { kbApi, workspacesApi, type Workspace } from "@/lib/platform-api";
import {
  Button, Card, EmptyState, ErrorText, Field, Input, PageHeader, SeverityBadge, StatusBadge,
  Table, TableRow,
} from "@/components/ui";
import { Markdown } from "@/components/Markdown";

const SEVERITIES = ["low", "medium", "high", "critical"];

export default function IncidentsPage() {
  const { token, isAdmin } = useAuth();
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [selected, setSelected] = useState<Incident | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [workspaceFilter, setWorkspaceFilter] = useState<string>("");

  useEffect(() => {
    if (!token) return;
    workspacesApi.list(token).then(setWorkspaces).catch(() => setWorkspaces([]));
  }, [token]);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const page = await listIncidents(token, 0, 20, workspaceFilter || undefined);
      setIncidents(page.items);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load incidents");
    } finally {
      setLoading(false);
    }
  }, [token, workspaceFilter]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader
        title="Incidents"
        subtitle="Track and resolve incidents in your organization."
        icon={<AlertTriangle className="h-6 w-6 text-accent" />}
        actions={
          <>
            {workspaces.length > 0 && (
              <select
                value={workspaceFilter}
                onChange={(e) => setWorkspaceFilter(e.target.value)}
                className="input h-10 w-auto px-3 text-sm"
              >
                <option value="">All workspaces</option>
                {workspaces.map((w) => (
                  <option key={w.id} value={w.id}>{w.name}</option>
                ))}
              </select>
            )}
            <button
              onClick={() => token && downloadIncidentsCsv(token).catch((e) => setError(e.message))}
              className="btn-ghost flex h-10 items-center gap-2 px-4 text-sm"
            >
              <Download className="h-4 w-4" /> Export CSV
            </button>
            <button
              onClick={() => setShowCreate((v) => !v)}
              className="btn-accent flex h-10 items-center gap-2 px-4 text-sm"
            >
              {showCreate ? <X className="h-4 w-4" /> : <Plus className="h-4 w-4" />}
              {showCreate ? "Close" : "New incident"}
            </button>
          </>
        }
      />

      {showCreate && <CreateForm workspaces={workspaces} onCreated={() => { setShowCreate(false); load(); }} />}
      <ErrorText message={error} />

      <Card className="mt-4 p-0">
        {loading ? (
          <p className="p-6 text-sm text-ink-soft">Loading…</p>
        ) : incidents.length === 0 ? (
          <EmptyState icon={<AlertTriangle className="h-8 w-8" />} title="No incidents yet" hint="Create your first one to get started." />
        ) : (
          <Table columns={["Title", "Severity", "Status", "Detected"]}>
            {incidents.map((i) => (
              <TableRow key={i.id} onClick={() => setSelected(i)}>
                <td className="px-5 py-3 font-medium">{i.title}</td>
                <td className="px-5 py-3"><SeverityBadge severity={i.severity} /></td>
                <td className="px-5 py-3"><StatusBadge status={i.status} /></td>
                <td className="px-5 py-3 text-ink-soft">
                  {i.detectedAt ? new Date(i.detectedAt).toLocaleString() : "—"}
                </td>
              </TableRow>
            ))}
          </Table>
        )}
      </Card>

      {selected && (
        <DetailDrawer
          incident={selected}
          canDelete={isAdmin}
          canRetag={isAdmin}
          workspaces={workspaces}
          onClose={() => setSelected(null)}
          onChanged={() => { setSelected(null); load(); }}
        />
      )}
    </div>
  );
}

function CreateForm({ workspaces, onCreated }: { workspaces: Workspace[]; onCreated: () => void }) {
  const { token } = useAuth();
  const [form, setForm] = useState({ title: "", severity: "medium", anomalyDescription: "", rawLogs: "", workspaceId: "" });
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!token) return;
    setSubmitting(true);
    setError(null);
    try {
      await createIncident(token, { ...form, workspaceId: form.workspaceId || undefined });
      onCreated();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create incident");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card className="mt-4">
      <form onSubmit={onSubmit} className="space-y-4">
        <Field label="Title">
          <Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} required />
        </Field>
        <Field label="Severity">
          <select
            value={form.severity}
            onChange={(e) => setForm({ ...form, severity: e.target.value })}
            className="input h-11 capitalize"
          >
            {SEVERITIES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </Field>
        <Field label="Description">
          <Input
            value={form.anomalyDescription}
            onChange={(e) => setForm({ ...form, anomalyDescription: e.target.value })}
          />
        </Field>
        {workspaces.length > 0 && (
          <Field label="Workspace (optional)">
            <select
              value={form.workspaceId}
              onChange={(e) => setForm({ ...form, workspaceId: e.target.value })}
              className="input h-11"
            >
              <option value="">No workspace</option>
              {workspaces.map((w) => (
                <option key={w.id} value={w.id}>{w.name}</option>
              ))}
            </select>
          </Field>
        )}
        <ErrorText message={error} />
        <div className="w-40">
          <Button type="submit" disabled={submitting}>{submitting ? "Creating…" : "Create"}</Button>
        </div>
      </form>
    </Card>
  );
}

function DetailDrawer({
  incident,
  canDelete,
  canRetag,
  workspaces,
  onClose,
  onChanged,
}: {
  incident: Incident;
  canDelete: boolean;
  canRetag: boolean;
  workspaces: Workspace[];
  onClose: () => void;
  onChanged: () => void;
}) {
  const { token } = useAuth();
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [workspaceId, setWorkspaceId] = useState(incident.workspaceId ?? "");
  const [retagging, setRetagging] = useState(false);
  const [retagMsg, setRetagMsg] = useState<string | null>(null);

  async function onRetag(newValue: string) {
    if (!token) return;
    setWorkspaceId(newValue);
    setRetagging(true);
    setRetagMsg(null);
    try {
      await setIncidentWorkspace(token, incident.id, newValue || null);
      setRetagMsg(newValue ? "Moved to workspace." : "Cleared — now visible to the whole org.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update workspace");
    } finally {
      setRetagging(false);
    }
  }
  const [aiBusy, setAiBusy] = useState<string | null>(null);
  const [rca, setRca] = useState<Record<string, unknown> | null>(null);
  const [codeFix, setCodeFix] = useState<Record<string, unknown> | null>(null);
  const [runbook, setRunbook] = useState<Record<string, unknown> | null>(null);
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);
  const [similar, setSimilar] = useState<SimilarIncident[]>([]);

  useEffect(() => {
    if (!token) return;
    incidentTimeline(token, incident.id).then(setTimeline).catch(() => setTimeline([]));
    incidentSimilar(token, incident.id).then(setSimilar).catch(() => setSimilar([]));
  }, [token, incident.id]);

  async function runRca() {
    if (!token) return;
    setAiBusy("rca");
    try { setRca(await incidentRcaTree(token, incident.id)); }
    catch (e) { setError(e instanceof Error ? e.message : "RCA failed"); }
    finally { setAiBusy(null); }
  }

  async function runCodeFix() {
    if (!token) return;
    setAiBusy("fix");
    try { setCodeFix(await incidentCodeFix(token, incident.id)); }
    catch (e) { setError(e instanceof Error ? e.message : "Code fix failed"); }
    finally { setAiBusy(null); }
  }

  async function runRunbook() {
    if (!token) return;
    setAiBusy("runbook");
    try { setRunbook(await incidentRunbook(token, incident.id)); }
    catch (e) { setError(e instanceof Error ? e.message : "Runbook failed"); }
    finally { setAiBusy(null); }
  }

  async function downloadPdf() {
    if (!token) return;
    setAiBusy("pdf");
    try { await downloadIncidentPdf(token, incident.id); }
    catch (e) { setError(e instanceof Error ? e.message : "PDF failed"); }
    finally { setAiBusy(null); }
  }

  const [kbMsg, setKbMsg] = useState<string | null>(null);
  async function generateKbArticle() {
    if (!token) return;
    setAiBusy("kb");
    setKbMsg(null);
    try {
      await kbApi.generate(token, incident.id);
      setKbMsg("Knowledge-base article created. See it on the Knowledge Base page.");
    } catch (e) {
      setKbMsg(e instanceof Error ? e.message : "Could not generate article");
    } finally {
      setAiBusy(null);
    }
  }

  async function onResolve() {
    if (!token) return;
    setBusy(true);
    setError(null);
    try {
      await resolveIncident(token, incident.id, notes || undefined);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to resolve");
      setBusy(false);
    }
  }

  async function onDelete() {
    if (!token) return;
    setBusy(true);
    setError(null);
    try {
      await deleteIncident(token, incident.id);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete");
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex justify-end bg-black/20" onClick={onClose}>
      <div
        className="h-full w-full max-w-md overflow-y-auto bg-surface p-6 shadow-card"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-start justify-between">
          <h2 className="text-lg font-semibold">{incident.title}</h2>
          <button onClick={onClose} className="text-ink-soft hover:text-ink" aria-label="Close">✕</button>
        </div>
        <div className="flex gap-2">
          <SeverityBadge severity={incident.severity} />
          <StatusBadge status={incident.status} />
        </div>

        <div className="mt-4">
          <dt className="text-xs uppercase tracking-wide text-ink-soft">Workspace</dt>
          {canRetag && workspaces.length > 0 ? (
            <div className="mt-1 flex items-center gap-2">
              <select
                value={workspaceId}
                onChange={(e) => onRetag(e.target.value)}
                disabled={retagging}
                className="input h-9 flex-1 text-sm"
              >
                <option value="">No workspace (visible to everyone)</option>
                {workspaces.map((w) => (
                  <option key={w.id} value={w.id}>{w.name}</option>
                ))}
              </select>
            </div>
          ) : (
            <dd className="mt-0.5 text-ink">
              {incident.workspaceId
                ? workspaces.find((w) => w.id === incident.workspaceId)?.name ?? "Restricted workspace"
                : "None — visible to everyone in your organization"}
            </dd>
          )}
          {retagMsg && <p className="mt-1 text-xs text-ink-soft">{retagMsg}</p>}
        </div>

        <dl className="mt-5 space-y-3 text-sm">
          <Detail label="Description" value={incident.anomalyDescription} />
          <Detail label="Root cause" value={incident.rootCause} />
          <Detail label="Detected" value={incident.detectedAt ? new Date(incident.detectedAt).toLocaleString() : null} />
          <Detail label="Resolved" value={incident.resolvedAt ? new Date(incident.resolvedAt).toLocaleString() : null} />
          <Detail label="Resolution notes" value={incident.resolutionNotes} />
        </dl>

        {/* Timeline */}
        {timeline.length > 0 && (
          <div className="mt-5">
            <p className="mb-2 text-xs uppercase tracking-wide text-ink-soft">Timeline</p>
            <ol className="relative ml-2 border-l border-line/15 pl-4">
              {timeline.map((e, i) => (
                <li key={i} className="mb-3 last:mb-0">
                  <span className="absolute -left-[5px] mt-1.5 h-2.5 w-2.5 rounded-full bg-accent" />
                  <p className="text-sm font-medium">{e.label}</p>
                  {e.at && <p className="text-xs text-ink-soft">{new Date(e.at).toLocaleString()}</p>}
                  {e.detail && <p className="text-xs text-ink-soft">{e.detail}</p>}
                </li>
              ))}
            </ol>
          </div>
        )}

        {/* Similar incidents */}
        {similar.length > 0 && (
          <div className="mt-5">
            <p className="mb-2 text-xs uppercase tracking-wide text-ink-soft">Similar incidents</p>
            <ul className="space-y-1">
              {similar.map((s) => (
                <li key={s.id} className="flex items-center justify-between rounded-lg bg-surface-2 px-3 py-2 text-sm">
                  <span className="truncate">{s.title}</span>
                  <span className="ml-2 shrink-0 text-xs text-ink-soft">{Math.round(s.score * 100)}% match</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* AI actions */}
        <div className="mt-5 flex gap-2">
          <button onClick={runRca} disabled={aiBusy !== null}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-xl border border-line/10 py-2 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50">
            <GitBranch className="h-3.5 w-3.5" /> {aiBusy === "rca" ? "Analyzing…" : "RCA tree"}
          </button>
          <button onClick={runCodeFix} disabled={aiBusy !== null}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-xl border border-line/10 py-2 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50">
            <Wrench className="h-3.5 w-3.5" /> {aiBusy === "fix" ? "Generating…" : "Suggest code fix"}
          </button>
        </div>
        <div className="mt-2 flex gap-2">
          <button onClick={runRunbook} disabled={aiBusy !== null}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-xl border border-line/10 py-2 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50">
            <Layers className="h-3.5 w-3.5" /> {aiBusy === "runbook" ? "Writing…" : "Generate runbook"}
          </button>
          <button onClick={downloadPdf} disabled={aiBusy !== null}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-xl border border-line/10 py-2 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50">
            <FileText className="h-3.5 w-3.5" /> {aiBusy === "pdf" ? "Preparing…" : "Download PDF"}
          </button>
        </div>

        {runbook && (
          <Card className="mt-3">
            <p className="text-xs uppercase tracking-wide text-ink-soft">Runbook — {String(runbook.title ?? "")}</p>
            {Array.isArray(runbook.steps) && (runbook.steps as RunbookStep[]).map((s, i) => (
              <div key={i} className="mt-2 text-sm">
                <span className="rounded bg-surface-2 px-1.5 py-0.5 text-xs uppercase text-ink-soft">{s.phase}</span>{" "}
                {s.action}
                {s.command && <code className="mt-1 block rounded bg-ink/90 px-2 py-1 font-mono text-xs text-white">{s.command}</code>}
              </div>
            ))}
          </Card>
        )}

        {rca && (
          <Card className="mt-3">
            <p className="text-xs uppercase tracking-wide text-ink-soft">Root cause analysis</p>
            <p className="mt-1 text-sm font-medium">{String(rca.incident_summary ?? "")}</p>
            <RcaTree node={rca.tree as TreeNode | undefined} />
          </Card>
        )}
        {codeFix && (
          <Card className="mt-3">
            <p className="text-xs uppercase tracking-wide text-ink-soft">Suggested fix</p>
            <p className="mt-1 text-sm">{String(codeFix.summary ?? "")}</p>
            {Array.isArray(codeFix.fixes) && (codeFix.fixes as Fix[]).map((f, i) => (
              <div key={i} className="mt-2">
                <p className="text-sm font-medium">{f.title} <span className="text-xs text-ink-soft">({f.risk} risk)</span></p>
                {f.code && <Markdown content={"```\n" + f.code + "\n```"} />}
              </div>
            ))}
          </Card>
        )}

        {incident.status !== "resolved" ? (
          <div className="mt-6 space-y-2">
            <Field label="Resolution notes (optional)">
              <Input value={notes} onChange={(e) => setNotes(e.target.value)} />
            </Field>
            <Button onClick={onResolve} disabled={busy}>{busy ? "Working…" : "Mark resolved"}</Button>
          </div>
        ) : (
          <div className="mt-6">
            <button
              onClick={generateKbArticle}
              disabled={aiBusy !== null}
              className="flex h-11 w-full items-center justify-center gap-2 rounded-xl border border-line/10 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50"
            >
              <Sparkles className="h-4 w-4 text-accent" />
              {aiBusy === "kb" ? "Generating…" : "Generate knowledge-base article"}
            </button>
            {kbMsg && <p className="mt-2 text-xs text-ink-soft">{kbMsg}</p>}
          </div>
        )}

        {canDelete && (
          <button
            onClick={onDelete}
            disabled={busy}
            className="mt-4 h-11 w-full rounded-xl border border-red-500/25 text-sm font-medium text-red-500 transition-colors hover:bg-red-500/10 disabled:opacity-50"
          >
            Delete incident
          </button>
        )}
        <ErrorText message={error} />
      </div>
    </div>
  );
}

function Detail({ label, value }: { label: string; value?: string | null }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-ink-soft">{label}</dt>
      <dd className="mt-0.5 text-ink">{value ?? "—"}</dd>
    </div>
  );
}

type TreeNode = { label: string; children?: TreeNode[] };
type Fix = { title: string; code: string; risk: string; rollback?: string };
type RunbookStep = { phase: string; action: string; command?: string };

function RcaTree({ node, depth = 0 }: { node?: TreeNode; depth?: number }) {
  if (!node) return null;
  return (
    <ul className={depth === 0 ? "mt-2" : "ml-4 border-l border-line/10 pl-3"}>
      <li className="py-0.5 text-sm">
        <span className={depth === 0 ? "font-medium" : ""}>{node.label}</span>
        {node.children?.map((c, i) => <RcaTree key={i} node={c} depth={depth + 1} />)}
      </li>
    </ul>
  );
}
