"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { motion } from "framer-motion";
import { useAuth } from "@/lib/auth-context";
import { jobsApi, type Job } from "@/lib/platform-api";
import { Card } from "@/components/ui";
import { LogInput } from "@/components/LogInput";
import { StatCard } from "@/components/StatCard";

export default function BulkPage() {
  const { token } = useAuth();
  const [source, setSource] = useState<string | null>(null);
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

  async function analyze(lines: string[], meta: string) {
    if (!token) return;
    if (lines.length === 0) { setError("No log lines found."); return; }
    setError(null);
    setSource(meta);
    try {
      const j = await jobsApi.bulkAnalyze(token, lines);
      setJob(j);
      startPoll(j.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to submit");
    }
  }

  const result = job?.result as {
    total_lines?: number; error_count?: number; warning_count?: number;
    summary?: { description?: string; severity?: string };
  } | null;
  const running = job?.status === "running" || job?.status === "pending";

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="text-2xl font-semibold tracking-tight">Bulk Log Analysis</h1>
      <p className="mt-1 text-sm text-ink-soft">Paste, upload (up to 10MB), or fetch logs from a URL — analyzed as a background job.</p>

      <Card className="mt-6">
        <LogInput onLines={analyze} />
        {source && <p className="mt-3 text-xs text-ink-soft">Source: {source}</p>}
        {error && <p className="mt-2 text-sm text-red-500">{error}</p>}
      </Card>

      {job && (
        <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}>
          <Card className="mt-6">
            <div className="flex items-center gap-2">
              {running && <span className="h-2 w-2 animate-pulse rounded-full bg-accent" />}
              <p className="text-sm">Status: <span className="font-medium capitalize">{job.status}</span></p>
            </div>
            {job.status === "failed" && <p className="mt-2 text-sm text-red-500">{job.error}</p>}
            {job.status === "completed" && result && (
              <>
                <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <StatCard label="Lines" value={result.total_lines ?? 0} />
                  <StatCard label="Errors" value={result.error_count ?? 0} accent={(result.error_count ?? 0) > 0} />
                  <StatCard label="Warnings" value={result.warning_count ?? 0} />
                  <StatCard label="Severity" value={result.summary?.severity ?? "—"} />
                </div>
                {result.summary?.description && (
                  <p className="mt-4 text-sm text-ink-soft">{result.summary.description}</p>
                )}
              </>
            )}
          </Card>
        </motion.div>
      )}
    </div>
  );
}
