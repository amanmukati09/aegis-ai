"use client";

import { useCallback, useEffect, useState } from "react";
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
                return (
                  <g key={n.id} onClick={() => onNodeClick(n.id)} style={{ cursor: "pointer" }}>
                    <circle cx={p.x} cy={p.y} r={r}
                      fill={blast?.component === n.id ? "#7c7aff" : "#7c7aff"}
                      fillOpacity={blast?.component === n.id ? 0.4 : 0.15} stroke="#7c7aff" />
                    <text x={p.x} y={p.y + 1} textAnchor="middle" dominantBaseline="middle" fontSize={11} className="fill-ink">
                      {n.label}
                    </text>
                    <text x={p.x} y={p.y + r + 12} textAnchor="middle" fontSize={10} className="fill-ink-soft">
                      {n.weight}
                    </text>
                  </g>
                );
              })}
            </svg>
          </div>
        )}
        {graph?.hasData && <p className="mt-2 text-center text-xs text-ink-soft">Click a component to see its blast radius.</p>}
      </Card>

      {blast && (
        <Card className="mt-4">
          <h2 className="text-sm font-medium">
            Blast radius — <span className="capitalize text-accent">{blast.component}</span>
            <span className="ml-2 text-xs text-ink-soft">{blast.radius} components impacted</span>
          </h2>
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
