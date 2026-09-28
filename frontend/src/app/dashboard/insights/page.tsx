"use client";

import { useCallback, useEffect, useState } from "react";
import { motion } from "framer-motion";
import { Activity, Gauge, TrendingUp, Boxes, RefreshCw } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import {
  insightsApi,
  type Benchmark,
  type Clusters,
  type HealthScore,
  type Predictions,
} from "@/lib/platform-api";
import { Card } from "@/components/ui";
import { BarBreakdown } from "@/components/Charts";

export default function InsightsPage() {
  const { token } = useAuth();
  const [health, setHealth] = useState<HealthScore | null>(null);
  const [bench, setBench] = useState<Benchmark | null>(null);
  const [preds, setPreds] = useState<Predictions | null>(null);
  const [clusters, setClusters] = useState<Clusters | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    const [h, b, p, c] = await Promise.allSettled([
      insightsApi.healthScore(token),
      insightsApi.benchmark(token),
      insightsApi.predictions(token),
      insightsApi.clusters(token),
    ]);
    if (h.status === "fulfilled") setHealth(h.value);
    if (b.status === "fulfilled") setBench(b.value);
    if (p.status === "fulfilled") setPreds(p.value);
    if (c.status === "fulfilled") setClusters(c.value);
    setLoading(false);
  }, [token]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="mx-auto max-w-5xl">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
            <Activity className="h-6 w-6 text-accent" /> Insights
          </h1>
          <p className="mt-1 text-sm text-ink-soft">
            System health, AI effectiveness, predictive patterns, and incident clusters.
          </p>
        </div>
        <button onClick={load} className="btn-ghost flex h-9 items-center gap-2 px-3 text-sm">
          <RefreshCw className={"h-4 w-4 " + (loading ? "animate-spin" : "")} /> Refresh
        </button>
      </div>

      {/* Health + benchmark row */}
      <div className="grid gap-4 md:grid-cols-3">
        <HealthCard health={health} />
        <div className="md:col-span-2">
          <BenchmarkCard bench={bench} />
        </div>
      </div>

      {/* Predictions */}
      <Card className="mt-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="flex items-center gap-2 text-sm font-medium">
            <TrendingUp className="h-4 w-4 text-accent" /> Predictive patterns
          </h2>
          {preds && <RiskBadge level={preds.riskLevel} />}
        </div>
        {preds && preds.predictions.length > 0 ? (
          <div className="space-y-2">
            {preds.predictions.map((p, i) => (
              <motion.div
                key={i}
                initial={{ opacity: 0, x: -8 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: i * 0.04 }}
                className="rounded-xl bg-surface-2 p-3"
              >
                <div className="flex items-center justify-between">
                  <p className="text-sm font-medium">{p.title}</p>
                  <span className="text-xs text-ink-soft">{p.confidence.toFixed(0)}%</span>
                </div>
                <p className="mt-0.5 text-xs text-ink-soft">{p.detail}</p>
                <div className="mt-2 h-1 overflow-hidden rounded-full bg-black/10 dark:bg-white/10">
                  <div className="h-full rounded-full bg-accent" style={{ width: `${p.confidence}%` }} />
                </div>
              </motion.div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-ink-soft">Not enough history for predictions yet.</p>
        )}
      </Card>

      {/* Clusters */}
      <Card className="mt-4">
        <h2 className="mb-3 flex items-center gap-2 text-sm font-medium">
          <Boxes className="h-4 w-4 text-accent" /> Incident clusters
        </h2>
        {clusters && clusters.clusters.length > 0 ? (
          <>
            <p className="mb-3 text-xs text-ink-soft">{clusters.summary}</p>
            <div className="grid gap-3 sm:grid-cols-2">
              {clusters.clusters.map((c, i) => (
                <motion.div
                  key={i}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: i * 0.04 }}
                  className="rounded-xl border border-line/10 p-3"
                >
                  <div className="flex items-center justify-between">
                    <p className="text-sm font-medium">{c.name}</p>
                    <span className="rounded-full bg-accent/15 px-2 py-0.5 text-xs font-medium text-accent">
                      {c.count}
                    </span>
                  </div>
                  <div className="mt-2 flex flex-wrap gap-1">
                    {c.commonFeatures.map((f) => (
                      <span key={f} className="rounded bg-surface-2 px-1.5 py-0.5 text-[10px] text-ink-soft">
                        {f}
                      </span>
                    ))}
                  </div>
                  <SeverityBar dist={c.severityDistribution} />
                </motion.div>
              ))}
            </div>
          </>
        ) : (
          <p className="text-sm text-ink-soft">
            {clusters ? clusters.summary : "Loading clusters…"}
          </p>
        )}
      </Card>
    </div>
  );
}

function HealthCard({ health }: { health: HealthScore | null }) {
  const score = health?.score ?? 0;
  const color =
    score >= 85 ? "text-emerald-500" : score >= 70 ? "text-green-500" : score >= 50 ? "text-amber-500" : "text-red-500";
  const ring =
    score >= 85 ? "#10b981" : score >= 70 ? "#22c55e" : score >= 50 ? "#f59e0b" : "#ef4444";
  return (
    <Card>
      <h2 className="flex items-center gap-2 text-sm font-medium">
        <Gauge className="h-4 w-4 text-accent" /> Health score
      </h2>
      <div className="mt-3 flex flex-col items-center">
        <div
          className="flex h-28 w-28 items-center justify-center rounded-full"
          style={{ background: `conic-gradient(${ring} ${score * 3.6}deg, rgba(120,120,120,0.15) 0deg)` }}
        >
          <div className="flex h-[88px] w-[88px] flex-col items-center justify-center rounded-full bg-surface">
            <span className={"text-3xl font-bold " + color}>{score}</span>
            <span className="text-[10px] uppercase text-ink-soft">{health?.status ?? "—"}</span>
          </div>
        </div>
        <div className="mt-3 grid w-full grid-cols-2 gap-2 text-center text-xs">
          <Metric label="Open" value={health?.openIncidents ?? "—"} />
          <Metric label="Resolved" value={health?.resolvedIncidents ?? "—"} />
          <Metric label="Critical 1h" value={health?.critical1h ?? "—"} />
          <Metric label="Top risk" value={health?.topRiskComponent ?? "—"} />
        </div>
      </div>
    </Card>
  );
}

function BenchmarkCard({ bench }: { bench: Benchmark | null }) {
  const data = bench
    ? [
        { metric: "Diagnosis", value: bench.diagnosisAccuracy },
        { metric: "Remediation", value: bench.remediationRate },
        { metric: "Resolution", value: bench.resolutionRate },
      ]
    : [];
  return (
    <Card className="h-full">
      <h2 className="mb-1 text-sm font-medium">AI benchmark</h2>
      <div className="mb-3 flex flex-wrap gap-4 text-xs text-ink-soft">
        <span>MTTR: <b className="text-ink">{bench?.avgResolutionHours ?? 0}h</b></span>
        <span>Last 7d: <b className="text-ink">{bench?.recent7d ?? 0}</b></span>
        <span>With root cause: <b className="text-ink">{bench?.withRootCause ?? 0}</b></span>
        <span>Total: <b className="text-ink">{bench?.totalIncidents ?? 0}</b></span>
      </div>
      {data.length > 0 ? (
        <BarBreakdown data={data} xKey="metric" yKey="value" />
      ) : (
        <p className="text-sm text-ink-soft">Loading…</p>
      )}
    </Card>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-lg bg-surface-2 py-1.5">
      <p className="truncate font-semibold">{value}</p>
      <p className="text-[10px] uppercase text-ink-soft">{label}</p>
    </div>
  );
}

function RiskBadge({ level }: { level: string }) {
  const tone =
    level === "HIGH"
      ? "bg-red-500/15 text-red-500"
      : level === "MEDIUM"
      ? "bg-amber-500/15 text-amber-500"
      : "bg-emerald-500/15 text-emerald-500";
  return <span className={"rounded-full px-2 py-0.5 text-xs font-medium " + tone}>{level} risk</span>;
}

function SeverityBar({ dist }: { dist: Record<string, number> }) {
  const colors: Record<string, string> = {
    critical: "#ef4444",
    high: "#f59e0b",
    medium: "#3b82f6",
    low: "#10b981",
    unknown: "#6b7280",
  };
  const total = Object.values(dist).reduce((a, b) => a + b, 0) || 1;
  return (
    <div className="mt-2 flex h-1.5 overflow-hidden rounded-full">
      {Object.entries(dist).map(([sev, count]) => (
        <div
          key={sev}
          title={`${sev}: ${count}`}
          style={{ width: `${(count / total) * 100}%`, background: colors[sev.toLowerCase()] ?? "#6b7280" }}
        />
      ))}
    </div>
  );
}
