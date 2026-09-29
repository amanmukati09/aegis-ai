"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { motion } from "framer-motion";
import { AlertOctagon, CheckCircle2, ChevronRight, Clock, FolderOpen } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { getDashboardSummary, type DashboardSummary } from "@/lib/incidents-api";
import { analyticsApi, type TimeseriesPoint } from "@/lib/platform-api";
import { Card, ErrorText, SeverityBadge, StatusBadge } from "@/components/ui";
import { AreaTrend, DonutBreakdown } from "@/components/Charts";
import { StatCard } from "@/components/StatCard";

export default function DashboardHome() {
  const { user, token } = useAuth();
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [series, setSeries] = useState<TimeseriesPoint[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!token) return;
    try {
      const [s, ts] = await Promise.all([
        getDashboardSummary(token),
        analyticsApi.timeseries(token).catch(() => []),
      ]);
      setSummary(s);
      setSeries(ts);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load dashboard");
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    load();
  }, [load]);

  const critical = summary?.bySeverity?.["critical"] ?? 0;
  const mttr = summary?.mttrHours;

  return (
    <div className="mx-auto max-w-5xl">
      <h1 className="text-2xl font-semibold tracking-tight">
        Welcome{user?.fullName ? `, ${user.fullName}` : ""}
      </h1>
      <p className="mt-1 text-ink-soft">Here&apos;s what&apos;s happening in your organization.</p>

      <ErrorText message={error} />

      <div className="mt-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Open" value={loading ? "—" : summary?.open ?? 0} icon={<FolderOpen className="h-4 w-4" />} />
        <StatCard label="Critical" value={loading ? "—" : critical} accent={critical > 0} icon={<AlertOctagon className="h-4 w-4" />} />
        <StatCard label="Resolved" value={loading ? "—" : summary?.resolved ?? 0} icon={<CheckCircle2 className="h-4 w-4" />} />
        <StatCard label="MTTR (hrs)" value={loading ? "—" : mttr == null ? "—" : mttr.toFixed(1)} icon={<Clock className="h-4 w-4" />} />
      </div>

      <Card className="mt-6">
        <h2 className="mb-3 text-sm font-medium text-ink">Incidents over the last 14 days</h2>
        <AreaTrend data={series as unknown as Record<string, unknown>[]} xKey="day" yKey="total" />
      </Card>

      <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <h2 className="mb-3 text-sm font-medium text-ink">By severity</h2>
          {summary && Object.keys(summary.bySeverity).length > 0 ? (
            <DonutBreakdown
              data={Object.entries(summary.bySeverity).map(([name, value]) => ({ name, value }))}
              nameKey="name"
              valueKey="value"
            />
          ) : (
            <Breakdown data={summary?.bySeverity} render={(k) => <SeverityBadge severity={k} />} />
          )}
        </Card>
        <Card>
          <h2 className="mb-3 text-sm font-medium text-ink">By status</h2>
          <Breakdown data={summary?.byStatus} render={(k) => <StatusBadge status={k} />} />
        </Card>
      </div>

      <Card className="mt-6 p-0">
        <div className="flex items-center justify-between px-5 pb-2 pt-5">
          <h2 className="text-sm font-medium text-ink">Recent incidents</h2>
          <Link href="/dashboard/incidents" className="flex items-center gap-1 text-sm text-accent hover:underline">
            View all <ChevronRight className="h-3.5 w-3.5" />
          </Link>
        </div>
        {summary && summary.recent.length > 0 ? (
          <ul>
            {summary.recent.map((i, idx) => (
              <motion.li
                key={i.id}
                initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: idx * 0.03 }}
                className="flex items-center justify-between border-t border-line/10 px-5 py-3 text-sm"
              >
                <span className="font-medium">{i.title}</span>
                <span className="flex items-center gap-2">
                  <SeverityBadge severity={i.severity} />
                  <StatusBadge status={i.status} />
                </span>
              </motion.li>
            ))}
          </ul>
        ) : (
          <p className="px-5 pb-5 text-sm text-ink-soft">{loading ? "Loading…" : "No incidents yet."}</p>
        )}
      </Card>
    </div>
  );
}

function Breakdown({
  data,
  render,
}: {
  data?: Record<string, number>;
  render: (key: string) => React.ReactNode;
}) {
  const entries = data ? Object.entries(data) : [];
  if (entries.length === 0) return <p className="text-sm text-ink-soft">No data.</p>;
  return (
    <ul className="space-y-2">
      {entries.map(([key, count]) => (
        <li key={key} className="flex items-center justify-between">
          {render(key)}
          <span className="text-sm font-medium text-ink">{count}</span>
        </li>
      ))}
    </ul>
  );
}
