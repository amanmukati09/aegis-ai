"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { liveApi, type LiveState } from "@/lib/platform-api";
import { Card, SeverityBadge, StatusBadge } from "@/components/ui";

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
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Live Monitor</h1>
          <p className="mt-1 text-sm text-ink-soft">
            Real-time org activity{state ? ` · updated ${new Date(state.timestamp).toLocaleTimeString()}` : ""}
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm text-ink-soft">
          <input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} />
          Auto-refresh
        </label>
      </div>

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
          <span className={"h-2 w-2 rounded-full " + (live ? "animate-pulse bg-green-500" : "bg-black/20")} />
          <h2 className="text-sm font-medium">Recent activity</h2>
        </div>
        {state && state.recent.length > 0 ? (
          <ul>
            {state.recent.map((r) => (
              <li key={r.id} className="flex items-center justify-between border-t border-black/5 px-5 py-3 text-sm">
                <span className="font-medium">{r.title}</span>
                <span className="flex items-center gap-2">
                  <SeverityBadge severity={r.severity} />
                  <StatusBadge status={r.status} />
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="px-5 pb-5 text-sm text-ink-soft">No recent activity.</p>
        )}
      </Card>
    </div>
  );
}
