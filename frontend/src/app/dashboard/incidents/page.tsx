"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import {
  createIncident,
  deleteIncident,
  downloadIncidentsCsv,
  incidentCodeFix,
  incidentRcaTree,
  incidentRunbook,
  downloadIncidentPdf,
  listIncidents,
  resolveIncident,
  type Incident,
} from "@/lib/incidents-api";
import { Button, Card, ErrorText, Field, Input, SeverityBadge, StatusBadge } from "@/components/ui";
import { Markdown } from "@/components/Markdown";

const SEVERITIES = ["low", "medium", "high", "critical"];

export default function IncidentsPage() {
  const { token, isAdmin } = useAuth();
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [selected, setSelected] = useState<Incident | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const page = await listIncidents(token);
      setIncidents(page.items);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load incidents");
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="mx-auto max-w-5xl">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Incidents</h1>
          <p className="mt-1 text-sm text-ink-soft">Track and resolve incidents in your organization.</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => token && downloadIncidentsCsv(token).catch((e) => setError(e.message))}
            className="h-10 rounded-xl border border-black/10 px-4 text-sm font-medium text-ink transition-colors hover:bg-surface-muted"
          >
            Export CSV
          </button>
          <button
            onClick={() => setShowCreate((v) => !v)}
            className="h-10 rounded-xl bg-accent px-4 text-sm font-medium text-white transition-colors hover:bg-accent-hover"
          >
            {showCreate ? "Close" : "New incident"}
          </button>
        </div>
      </div>

      {showCreate && <CreateForm onCreated={() => { setShowCreate(false); load(); }} />}
      <ErrorText message={error} />

      <Card className="mt-4 p-0">
        {loading ? (
          <p className="p-6 text-sm text-ink-soft">Loading…</p>
        ) : incidents.length === 0 ? (
          <p className="p-6 text-sm text-ink-soft">No incidents yet. Create your first one.</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-black/5 text-left text-xs uppercase tracking-wide text-ink-soft">
                <th className="px-5 py-3 font-medium">Title</th>
                <th className="px-5 py-3 font-medium">Severity</th>
                <th className="px-5 py-3 font-medium">Status</th>
                <th className="px-5 py-3 font-medium">Detected</th>
              </tr>
            </thead>
            <tbody>
              {incidents.map((i) => (
                <tr
                  key={i.id}
                  onClick={() => setSelected(i)}
                  className="cursor-pointer border-b border-black/5 transition-colors last:border-0 hover:bg-surface-muted"
                >
                  <td className="px-5 py-3 font-medium">{i.title}</td>
                  <td className="px-5 py-3"><SeverityBadge severity={i.severity} /></td>
                  <td className="px-5 py-3"><StatusBadge status={i.status} /></td>
                  <td className="px-5 py-3 text-ink-soft">
                    {i.detectedAt ? new Date(i.detectedAt).toLocaleString() : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {selected && (
        <DetailDrawer
          incident={selected}
          canDelete={isAdmin}
          onClose={() => setSelected(null)}
          onChanged={() => { setSelected(null); load(); }}
        />
      )}
    </div>
  );
}

function CreateForm({ onCreated }: { onCreated: () => void }) {
  const { token } = useAuth();
  const [form, setForm] = useState({ title: "", severity: "medium", anomalyDescription: "", rawLogs: "" });
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!token) return;
    setSubmitting(true);
    setError(null);
    try {
      await createIncident(token, form);
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
            className="h-11 w-full rounded-xl border border-black/10 bg-surface px-4 text-sm capitalize outline-none focus:ring-2 focus:ring-accent/40"
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
  onClose,
  onChanged,
}: {
  incident: Incident;
  canDelete: boolean;
  onClose: () => void;
  onChanged: () => void;
}) {
  const { token } = useAuth();
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [aiBusy, setAiBusy] = useState<string | null>(null);
  const [rca, setRca] = useState<Record<string, unknown> | null>(null);
  const [codeFix, setCodeFix] = useState<Record<string, unknown> | null>(null);
  const [runbook, setRunbook] = useState<Record<string, unknown> | null>(null);

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

        <dl className="mt-5 space-y-3 text-sm">
          <Detail label="Description" value={incident.anomalyDescription} />
          <Detail label="Root cause" value={incident.rootCause} />
          <Detail label="Detected" value={incident.detectedAt ? new Date(incident.detectedAt).toLocaleString() : null} />
          <Detail label="Resolved" value={incident.resolvedAt ? new Date(incident.resolvedAt).toLocaleString() : null} />
          <Detail label="Resolution notes" value={incident.resolutionNotes} />
        </dl>

        {/* AI actions */}
        <div className="mt-5 flex gap-2">
          <button onClick={runRca} disabled={aiBusy !== null}
            className="flex-1 rounded-xl border border-line/10 py-2 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50">
            {aiBusy === "rca" ? "Analyzing…" : "RCA tree"}
          </button>
          <button onClick={runCodeFix} disabled={aiBusy !== null}
            className="flex-1 rounded-xl border border-line/10 py-2 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50">
            {aiBusy === "fix" ? "Generating…" : "Suggest code fix"}
          </button>
        </div>
        <div className="mt-2 flex gap-2">
          <button onClick={runRunbook} disabled={aiBusy !== null}
            className="flex-1 rounded-xl border border-line/10 py-2 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50">
            {aiBusy === "runbook" ? "Writing…" : "Generate runbook"}
          </button>
          <button onClick={downloadPdf} disabled={aiBusy !== null}
            className="flex-1 rounded-xl border border-line/10 py-2 text-sm font-medium text-ink transition-colors hover:bg-surface-2 disabled:opacity-50">
            {aiBusy === "pdf" ? "Preparing…" : "Download PDF"}
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

        {incident.status !== "resolved" && (
          <div className="mt-6 space-y-2">
            <Field label="Resolution notes (optional)">
              <Input value={notes} onChange={(e) => setNotes(e.target.value)} />
            </Field>
            <Button onClick={onResolve} disabled={busy}>{busy ? "Working…" : "Mark resolved"}</Button>
          </div>
        )}

        {canDelete && (
          <button
            onClick={onDelete}
            disabled={busy}
            className="mt-4 h-11 w-full rounded-xl border border-red-200 text-sm font-medium text-red-600 transition-colors hover:bg-red-50 disabled:opacity-50"
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
    <ul className={depth === 0 ? "mt-2" : "ml-4 border-l border-black/10 pl-3"}>
      <li className="py-0.5 text-sm">
        <span className={depth === 0 ? "font-medium" : ""}>{node.label}</span>
        {node.children?.map((c, i) => <RcaTree key={i} node={c} depth={depth + 1} />)}
      </li>
    </ul>
  );
}
