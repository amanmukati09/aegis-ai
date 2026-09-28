"use client";

import Link from "next/link";
import { useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { diagnose, type DiagnoseResult } from "@/lib/diagnosis-api";
import { Button, Card, ErrorText, SeverityBadge } from "@/components/ui";

const SAMPLE = `2026-09-28 14:02:11 ERROR api-gateway: upstream timeout after 30s
2026-09-28 14:02:12 WARN db-pool: 95% connections in use
2026-09-28 14:02:13 ERROR payments-svc: connection refused to db:5432
2026-09-28 14:02:15 ERROR api-gateway: 503 Service Unavailable rate 62%`;

export default function DiagnosisPage() {
  const { token } = useAuth();
  const [logs, setLogs] = useState("");
  const [result, setResult] = useState<DiagnoseResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onAnalyze() {
    if (!token) return;
    const lines = logs.split("\n").map((l) => l.trim()).filter(Boolean);
    if (lines.length === 0) {
      setError("Paste some logs first.");
      return;
    }
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await diagnose(token, lines));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Diagnosis failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="text-2xl font-semibold tracking-tight">AI Diagnosis</h1>
      <p className="mt-1 text-sm text-ink-soft">
        Paste logs and let AegisAI detect the anomaly, attribute a root cause, and suggest remediation.
        An incident is created automatically.
      </p>

      <Card className="mt-6">
        <textarea
          value={logs}
          onChange={(e) => setLogs(e.target.value)}
          placeholder="Paste raw logs here…"
          rows={10}
          className="w-full rounded-xl border border-black/10 bg-surface-muted p-4 font-mono text-xs text-ink outline-none focus:ring-2 focus:ring-accent/40"
        />
        <div className="mt-3 flex items-center gap-3">
          <div className="w-40">
            <Button onClick={onAnalyze} disabled={loading}>
              {loading ? "Analyzing…" : "Analyze"}
            </Button>
          </div>
          <button
            onClick={() => setLogs(SAMPLE)}
            className="text-sm text-accent hover:underline"
          >
            Use sample logs
          </button>
        </div>
        <ErrorText message={error} />
      </Card>

      {result && (
        <div className="mt-6 space-y-4">
          <Card>
            <div className="mb-2 flex items-center justify-between">
              <h2 className="text-sm font-medium text-ink">Anomaly</h2>
              <SeverityBadge severity={result.anomaly.severity} />
            </div>
            <p className="text-sm text-ink">{result.anomaly.description || "—"}</p>
            <p className="mt-2 text-xs text-ink-soft">
              Type: {result.anomaly.anomaly_type} · Component: {result.anomaly.affected_component}
            </p>
          </Card>

          <Card>
            <h2 className="mb-2 text-sm font-medium text-ink">
              Root cause{" "}
              <span className="text-ink-soft">
                ({Math.round((result.diagnosis.confidence ?? 0) * 100)}% confidence)
              </span>
            </h2>
            <p className="text-sm text-ink">{result.diagnosis.root_cause || "—"}</p>
            {result.diagnosis.contributing_factors.length > 0 && (
              <>
                <p className="mt-3 text-xs uppercase tracking-wide text-ink-soft">Contributing factors</p>
                <ul className="mt-1 list-inside list-disc text-sm text-ink">
                  {result.diagnosis.contributing_factors.map((f, i) => <li key={i}>{f}</li>)}
                </ul>
              </>
            )}
          </Card>

          <Card>
            <h2 className="mb-2 text-sm font-medium text-ink">Remediation</h2>
            <RemediationList title="Immediate actions" items={result.remediation.immediate_actions} />
            <RemediationList title="Diagnostic commands" items={result.remediation.diagnostic_commands} mono />
            <RemediationList title="Prevention" items={result.remediation.prevention_measures} />
            <p className="mt-3 text-xs text-ink-soft">
              Estimated recovery: {result.remediation.estimated_recovery_time}
              {result.remediation.escalation_needed ? " · escalation recommended" : ""}
            </p>
          </Card>

          <Link
            href="/dashboard/incidents"
            className="inline-block text-sm text-accent hover:underline"
          >
            Incident #{result.incidentId.slice(0, 8)} created — view in Incidents →
          </Link>
        </div>
      )}
    </div>
  );
}

function RemediationList({ title, items, mono = false }: { title: string; items: string[]; mono?: boolean }) {
  if (!items || items.length === 0) return null;
  return (
    <div className="mt-3 first:mt-0">
      <p className="text-xs uppercase tracking-wide text-ink-soft">{title}</p>
      <ul className={"mt-1 space-y-1 text-sm text-ink " + (mono ? "font-mono text-xs" : "")}>
        {items.map((item, i) => (
          <li key={i} className={mono ? "rounded bg-surface-muted px-2 py-1" : "list-inside list-disc"}>
            {item}
          </li>
        ))}
      </ul>
    </div>
  );
}
