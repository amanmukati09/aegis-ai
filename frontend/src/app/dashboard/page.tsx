"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { getDashboardSummary, type DashboardSummary } from "@/lib/incidents-api";
import { analyticsApi, type TimeseriesPoint } from "@/lib/platform-api";
import { Card, ErrorText, SeverityBadge, StatusBadge } from "@/components/ui";
import { DonutBreakdown, LineTrend } from "@/components/Charts";

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
        <Stat label="Open" value={loading ? "…" : summary?.open ?? 0} />
        <Stat label="Critical" value={loading ? "…" : critical} accent={critical > 0} />
        <Stat label="Resolved" value={loading ? "…" : summary?.resolved ?? 0} />
        <Stat label="MTTR (hrs)" value={loading ? "…" : mttr == null ? "—" : mttr.toFixed(1)} />
      </div>

      <Card className="mt-6">
        <h2 className="mb-3 text-sm font-medium text-ink">Incidents over the last 14 days</h2>
        {series.length > 0 ? (
          <LineTrend data={series as unknown as Record<string, unknown>[]} xKey="day" yKey="total" />
        ) : (
          <p className="py-8 text-center text-sm text-ink-soft">{loading ? "Loading…" : "No data yet."}</p>
        )}
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
          <Link href="/dashboard/incidents" className="text-sm text-accent hover:underline">
            View all
          </Link>
        </div>
        {summary && summary.recent.length > 0 ? (
          <ul>
            {summary.recent.map((i) => (
              <li
                key={i.id}
                className="flex items-center justify-between border-t border-black/5 px-5 py-3 text-sm"
              >
                <span className="font-medium">{i.title}</span>
                <span className="flex items-center gap-2">
                  <SeverityBadge severity={i.severity} />
                  <StatusBadge status={i.status} />
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="px-5 pb-5 text-sm text-ink-soft">{loading ? "Loading…" : "No incidents yet."}</p>
        )}
      </Card>
    </div>
  );
}

function Stat({ label, value, accent = false }: { label: string; value: React.ReactNode; accent?: boolean }) {
  return (
    <Card>
      <p className="text-sm text-ink-soft">{label}</p>
      <p className={"mt-1 text-2xl font-semibold " + (accent ? "text-red-600" : "text-ink")}>{value}</p>
    </Card>
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
