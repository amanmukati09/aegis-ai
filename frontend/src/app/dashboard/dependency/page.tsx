"use client";

import { useCallback, useEffect, useState } from "react";
import { Waypoints } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { dependencyApi, type BlastRadius, type DependencyGraph } from "@/lib/platform-api";
import { Card } from "@/components/ui";

/**
 * Lightweight radial dependency view: components placed on a circle, edges drawn as
 * lines, node size scaled by incident weight. No heavy graph lib — an inline SVG,
 * which keeps the bundle small and renders crisply.
 */
export default function DependencyPage() {
  const { token } = useAuth();
  const [graph, setGraph] = useState<DependencyGraph | null>(null);
  const [loading, setLoading] = useState(true);
  const [blast, setBlast] = useState<BlastRadius | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    try {
      setGraph(await dependencyApi.graph(token));
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => { load(); }, [load]);

  async function onNodeClick(id: string) {
    if (!token) return;
    try { setBlast(await dependencyApi.blastRadius(token, id)); } catch { setBlast(null); }
  }

  const size = 520;
  const cx = size / 2;
  const cy = size / 2;
  const radius = 190;
  const nodes = graph?.nodes ?? [];
  const positions = new Map<string, { x: number; y: number }>();
  nodes.forEach((n, i) => {
    const angle = (2 * Math.PI * i) / Math.max(nodes.length, 1) - Math.PI / 2;
    positions.set(n.id, { x: cx + radius * Math.cos(angle), y: cy + radius * Math.sin(angle) });
  });
  const maxWeight = Math.max(1, ...nodes.map((n) => n.weight));

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="text-2xl font-semibold tracking-tight">Dependency Map</h1>
      <p className="mt-1 text-sm text-ink-soft">Component relationships inferred from incident co-occurrence.</p>

      <Card className="mt-6">
        {loading ? (
          <p className="py-16 text-center text-sm text-ink-soft">Loading…</p>
        ) : !graph?.hasData ? (
          <p className="py-16 text-center text-sm text-ink-soft">
            Not enough incident data yet. Run some diagnoses to build the map.
          </p>
        ) : (
          <div className="flex justify-center overflow-x-auto">
            <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="max-w-full">
              {graph.edges.map((e, i) => {
                const a = positions.get(e.source);
                const b = positions.get(e.target);
                if (!a || !b) return null;
                return <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke="#7c7aff" strokeOpacity={0.25} strokeWidth={1 + e.weight} />;
              })}
              {nodes.map((n) => {
                const p = positions.get(n.id)!;
                const r = 16 + 20 * (n.weight / maxWeight);
                const hc = healthColor(n.healthScore ?? 100);
                return (
                  <g key={n.id} onClick={() => onNodeClick(n.id)} style={{ cursor: "pointer" }}>
                    <circle cx={p.x} cy={p.y} r={r}
                      fill={hc}
                      fillOpacity={blast?.component === n.id ? 0.45 : 0.18} stroke={hc} />
                    <text x={p.x} y={p.y + 1} textAnchor="middle" dominantBaseline="middle" fontSize={11} className="fill-ink">
                      {n.label}
                    </text>
                    <text x={p.x} y={p.y + r + 12} textAnchor="middle" fontSize={10} className="fill-ink-soft">
                      {n.weight} · {n.healthScore ?? 100}
                    </text>
                  </g>
                );
              })}
            </svg>
          </div>
        )}
        {graph?.hasData && (
          <p className="mt-2 text-center text-xs text-ink-soft">
            Click a component to see its blast radius. Node color reflects health score.
          </p>
        )}
      </Card>

      {graph?.criticalPaths && graph.criticalPaths.length > 0 && (
        <Card className="mt-4">
          <h2 className="mb-3 flex items-center gap-2 text-sm font-medium">
            <Waypoints className="h-4 w-4 text-accent" /> Critical paths (structural hubs)
          </h2>
          <div className="grid gap-3 sm:grid-cols-3">
            {graph.criticalPaths.map((cp) => (
              <div key={cp.component} className="rounded-xl border border-line/10 p-3">
                <div className="flex items-center justify-between">
                  <p className="truncate text-sm font-medium capitalize">{cp.component}</p>
                  <span className={"rounded-full px-2 py-0.5 text-[10px] font-medium uppercase " +
                    (cp.risk === "high" ? "bg-red-500/15 text-red-500" : "bg-amber-500/15 text-amber-500")}>
                    {cp.risk}
                  </span>
                </div>
                <p className="mt-1 text-xs text-ink-soft">
                  {cp.connections} connections · health {cp.healthScore}
                </p>
              </div>
            ))}
          </div>
          <p className="mt-3 text-xs text-ink-soft">
            High-degree components — a failure here has the widest blast radius. Prioritize resilience work here first.
          </p>
        </Card>
      )}

      {blast && (
        <Card className="mt-4">
          <h2 className="text-sm font-medium">
            Blast radius — <span className="capitalize text-accent">{blast.component}</span>
            <span className="ml-2 text-xs text-ink-soft">{blast.radius} components impacted</span>
            {blast.severity && (
              <span className={"ml-2 rounded-full px-2 py-0.5 text-[10px] font-medium uppercase " + sevBadge(blast.severity)}>
                {blast.severity}
              </span>
            )}
          </h2>
          {blast.totalIncidentImpact != null && (
            <p className="mt-1 text-xs text-ink-soft">{blast.totalIncidentImpact} total incidents across the impacted set</p>
          )}
          <div className="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <p className="text-xs uppercase tracking-wide text-ink-soft">Direct impact</p>
              <div className="mt-1 flex flex-wrap gap-1.5">
                {blast.directImpact.length ? blast.directImpact.map((c) => (
                  <span key={c} className="rounded-full bg-orange-500/15 px-2.5 py-0.5 text-xs capitalize text-orange-500">{c}</span>
                )) : <span className="text-xs text-ink-soft">None</span>}
              </div>
            </div>
            <div>
              <p className="text-xs uppercase tracking-wide text-ink-soft">Indirect impact</p>
              <div className="mt-1 flex flex-wrap gap-1.5">
                {blast.indirectImpact.length ? blast.indirectImpact.map((c) => (
                  <span key={c} className="rounded-full bg-amber-500/15 px-2.5 py-0.5 text-xs capitalize text-amber-500">{c}</span>
                )) : <span className="text-xs text-ink-soft">None</span>}
              </div>
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}

function healthColor(score: number): string {
  if (score >= 85) return "#22c55e";
  if (score >= 60) return "#f59e0b";
  return "#ef4444";
}

function sevBadge(sev: string): string {
  if (sev === "critical") return "bg-red-500/15 text-red-500";
  if (sev === "high") return "bg-orange-500/15 text-orange-500";
  return "bg-amber-500/15 text-amber-500";
}
