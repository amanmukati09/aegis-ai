"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { jobsApi, type Job } from "@/lib/platform-api";
import { Button, Card, ErrorText } from "@/components/ui";

const SAMPLE = `2026-09-28 ERROR db connection refused
2026-09-28 WARN pool 95% used
2026-09-28 ERROR 503 rate 62%`;

export default function BulkPage() {
  const { token } = useAuth();
  const [logs, setLogs] = useState("");
  const [job, setJob] = useState<Job | null>(null);
  const [error, setError] = useState<string | null>(null);
  const poll = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPoll = () => { if (poll.current) { clearInterval(poll.current); poll.current = null; } };

  useEffect(() => () => stopPoll(), []);

  const startPoll = useCallback((id: string) => {
    stopPoll();
    poll.current = setInterval(async () => {
      if (!token) return;
      try {
        const j = await jobsApi.get(token, id);
        setJob(j);
        if (j.status === "completed" || j.status === "failed") stopPoll();
      } catch { stopPoll(); }
    }, 1500);
  }, [token]);

  async function onSubmit() {
    if (!token) return;
    const lines = logs.split("\n").map((l) => l.trim()).filter(Boolean);
    if (lines.length === 0) { setError("Paste some logs first."); return; }
    setError(null);
    try {
      const j = await jobsApi.bulkAnalyze(token, lines);
      setJob(j);
      startPoll(j.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to submit");
    }
  }

  const result = job?.result as { total_lines?: number; error_count?: number; warning_count?: number; summary?: { description?: string; severity?: string } } | null;

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="text-2xl font-semibold tracking-tight">Bulk Log Analysis</h1>
      <p className="mt-1 text-sm text-ink-soft">Analyze large log batches as a background job.</p>

      <Card className="mt-6">
        <textarea value={logs} onChange={(e) => setLogs(e.target.value)} rows={10} placeholder="Paste logs…"
          className="w-full rounded-xl border border-black/10 bg-surface-muted p-4 font-mono text-xs outline-none focus:ring-2 focus:ring-accent/40" />
        <div className="mt-3 flex items-center gap-3">
          <div className="w-40"><Button onClick={onSubmit} disabled={job?.status === "running" || job?.status === "pending"}>
            {job && (job.status === "running" || job.status === "pending") ? "Analyzing…" : "Analyze"}
          </Button></div>
          <button onClick={() => setLogs(SAMPLE)} className="text-sm text-accent hover:underline">Use sample</button>
        </div>
        <ErrorText message={error} />
      </Card>

      {job && (
        <Card className="mt-4">
          <p className="text-sm">Job status: <span className="font-medium capitalize">{job.status}</span></p>
          {job.status === "failed" && <ErrorText message={job.error} />}
          {job.status === "completed" && result && (
            <div className="mt-3 grid grid-cols-3 gap-3 text-center">
              <Stat label="Lines" value={result.total_lines ?? 0} />
              <Stat label="Errors" value={result.error_count ?? 0} />
              <Stat label="Warnings" value={result.warning_count ?? 0} />
              {result.summary?.description && (
                <p className="col-span-3 mt-2 text-left text-sm text-ink-soft">{result.summary.description}</p>
              )}
            </div>
          )}
        </Card>
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-xl bg-surface-muted p-3">
      <p className="text-xs text-ink-soft">{label}</p>
      <p className="mt-1 text-xl font-semibold">{value}</p>
    </div>
  );
}
