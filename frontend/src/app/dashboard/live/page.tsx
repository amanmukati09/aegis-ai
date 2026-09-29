"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { motion } from "framer-motion";
import { Activity, Radio } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { liveApi, type LiveState } from "@/lib/platform-api";
import { Card, EmptyState, PageHeader, SeverityBadge, StatusBadge } from "@/components/ui";

/**
 * Live monitor: polls the org state every 4s for a near-real-time feed. (A WebSocket
 * push channel is a drop-in upgrade; polling is robust through the proxy.)
 */
export default function LivePage() {
  const { token } = useAuth();
  const [state, setState] = useState<LiveState | null>(null);
  const [live, setLive] = useState(true);
  const poll = useRef<ReturnType<typeof setInterval> | null>(null);

  const tick = useCallback(async () => {
    if (!token) return;
    try { setState(await liveApi.state(token)); } catch { /* keep last */ }
  }, [token]);

  useEffect(() => {
    tick();
    if (live) {
      poll.current = setInterval(tick, 4000);
    }
    return () => { if (poll.current) clearInterval(poll.current); };
  }, [tick, live]);

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title="Live Monitor"
        subtitle={`Real-time org activity${state ? ` · updated ${new Date(state.timestamp).toLocaleTimeString()}` : ""}`}
        icon={<Radio className="h-6 w-6 text-accent" />}
        actions={
          <label className="flex items-center gap-2 text-sm text-ink-soft">
            <input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} className="accent-accent" />
            Auto-refresh
          </label>
        }
      />

      <div className="grid grid-cols-2 gap-4">
        <Card>
          <p className="text-sm text-ink-soft">Total incidents</p>
          <p className="mt-1 text-3xl font-semibold">{state?.total ?? "—"}</p>
        </Card>
        <Card>
          <p className="text-sm text-ink-soft">Open</p>
          <p className="mt-1 text-3xl font-semibold text-accent">{state?.open ?? "—"}</p>
        </Card>
      </div>

      <Card className="mt-4 p-0">
        <div className="flex items-center gap-2 px-5 py-3">
          <span className={"h-2 w-2 rounded-full " + (live ? "animate-pulse bg-emerald-500" : "bg-ink-soft/30")} />
          <h2 className="flex items-center gap-2 text-sm font-medium">
            <Activity className="h-4 w-4 text-accent" /> Recent activity
          </h2>
        </div>
        {state && state.recent.length > 0 ? (
          <ul>
            {state.recent.map((r, i) => (
              <motion.li
                key={r.id}
                initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.03 }}
                className="flex items-center justify-between border-t border-line/10 px-5 py-3 text-sm"
              >
                <span className="font-medium">{r.title}</span>
                <span className="flex items-center gap-2">
                  <SeverityBadge severity={r.severity} />
                  <StatusBadge status={r.status} />
                </span>
              </motion.li>
            ))}
          </ul>
        ) : (
          <EmptyState icon={<Radio className="h-8 w-8" />} title="No recent activity" />
        )}
      </Card>
    </div>
  );
}
