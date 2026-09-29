"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { motion } from "framer-motion";
import { FileText, CheckCircle2, Download, ExternalLink } from "lucide-react";
import Link from "next/link";
import { useAuth } from "@/lib/auth-context";
import { jobsApi, downloadBulkReportPdf, type Job } from "@/lib/platform-api";
import { Card } from "@/components/ui";
import { LogInput } from "@/components/LogInput";
import { StatCard } from "@/components/StatCard";

export default function BulkPage() {
  const { token } = useAuth();
  const [source, setSource] = useState<string | null>(null);
  const [job, setJob] = useState<Job | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
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
    setJob(null);
    try {
      const j = await jobsApi.bulkAnalyze(token, lines, meta, true);
      setJob(j);
      startPoll(j.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to submit");
    }
  }

  async function onDownloadPdf() {
    if (!token || !job) return;
    setDownloading(true);
    try { await downloadBulkReportPdf(token, job.id); }
    catch (e) { setError(e instanceof Error ? e.message : "PDF failed"); }
    finally { setDownloading(false); }
  }

  const result = job?.result ?? null;
  const running = job?.status === "running" || job?.status === "pending";
  const incidents = result?.incidents ?? [];

  const sevColor = (s: string) =>
    s === "critical" ? "bg-red-500/15 text-red-500 ring-red-500/30"
    : s === "high" ? "bg-amber-500/15 text-amber-500 ring-amber-500/30"
    : s === "medium" ? "bg-sky-500/15 text-sky-500 ring-sky-500/30"
    : "bg-emerald-500/15 text-emerald-500 ring-emerald-500/30";

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="text-2xl font-semibold tracking-tight">Bulk Log Analysis</h1>
      <p className="mt-1 text-sm text-ink-soft">
        Paste, upload (up to 10MB), or fetch logs from a URL. AegisAI segments the stream into
        distinct incidents, creates them, and builds a combined report.
      </p>

      <Card className="mt-6">
        <LogInput onLines={analyze} />
        {source && <p className="mt-3 text-xs text-ink-soft">Source: {source}</p>}
        {error && <p className="mt-2 text-sm text-red-500">{error}</p>}
      </Card>

      {job && (
        <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}>
          <Card className="mt-6">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                {running && <span className="h-2 w-2 animate-pulse rounded-full bg-accent" />}
                <p className="text-sm">Status: <span className="font-medium capitalize">{job.status}</span></p>
              </div>
              {job.status === "completed" && (
                <button
                  onClick={onDownloadPdf}
                  disabled={downloading}
                  className="btn-ghost flex h-9 items-center gap-2 px-3 text-sm disabled:opacity-50"
                >
                  <Download className="h-4 w-4" /> {downloading ? "Preparing…" : "Download PDF"}
                </button>
              )}
            </div>

            {job.status === "failed" && <p className="mt-2 text-sm text-red-500">{job.error}</p>}

            {job.status === "completed" && result && (
              <>
                <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <StatCard label="Lines" value={result.total_lines ?? 0} />
                  <StatCard label="Incidents found" value={result.incident_count ?? 0}
                    accent={(result.incident_count ?? 0) > 0} />
                  <StatCard label="Created" value={result.created_count ?? 0} />
                  <StatCard label="Error lines" value={result.error_lines ?? 0} />
                </div>

                {(result.created_count ?? 0) > 0 && (
                  <p className="mt-4 flex items-center gap-2 text-sm text-emerald-500">
                    <CheckCircle2 className="h-4 w-4" />
                    {result.created_count} incidents created and added to your incident list.
                    <Link href="/dashboard/incidents" className="inline-flex items-center gap-1 text-accent hover:underline">
                      View <ExternalLink className="h-3 w-3" />
                    </Link>
                  </p>
                )}

                {incidents.length > 0 && (
                  <div className="mt-5">
                    <h2 className="mb-2 flex items-center gap-2 text-sm font-medium">
                      <FileText className="h-4 w-4 text-accent" /> Detected incidents
                    </h2>
                    <div className="space-y-2">
                      {incidents.map((inc, i) => (
                        <motion.div
                          key={i}
                          initial={{ opacity: 0, x: -8 }}
                          animate={{ opacity: 1, x: 0 }}
                          transition={{ delay: i * 0.03 }}
                          className="rounded-xl border border-line/10 p-3"
                        >
                          <div className="flex items-center justify-between gap-2">
                            <p className="truncate text-sm font-medium">{inc.title}</p>
                            <span className={"shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium uppercase ring-1 " + sevColor(inc.severity)}>
                              {inc.severity}
                            </span>
                          </div>
                          <p className="mt-0.5 text-xs text-ink-soft">{inc.description}</p>
                          <div className="mt-1 flex flex-wrap gap-1 text-[10px] text-ink-soft">
                            <span className="rounded bg-surface-2 px-1.5 py-0.5">{inc.component}</span>
                            <span className="rounded bg-surface-2 px-1.5 py-0.5">{inc.count} entries</span>
                            {inc.first_line != null && (
                              <span className="rounded bg-surface-2 px-1.5 py-0.5">lines {inc.first_line}–{inc.last_line}</span>
                            )}
                          </div>
                        </motion.div>
                      ))}
                    </div>
                  </div>
                )}
              </>
            )}
          </Card>
        </motion.div>
      )}
    </div>
  );
}
