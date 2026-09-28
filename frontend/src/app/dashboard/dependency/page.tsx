"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { dependencyApi, type DependencyGraph } from "@/lib/platform-api";
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

  const load = useCallback(async () => {
    if (!token) return;
    try {
      setGraph(await dependencyApi.graph(token));
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => { load(); }, [load]);

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
                return <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke="#0071e3" strokeOpacity={0.25} strokeWidth={1 + e.weight} />;
              })}
              {nodes.map((n) => {
                const p = positions.get(n.id)!;
                const r = 16 + 20 * (n.weight / maxWeight);
                return (
                  <g key={n.id}>
                    <circle cx={p.x} cy={p.y} r={r} fill="#0071e3" fillOpacity={0.15} stroke="#0071e3" />
                    <text x={p.x} y={p.y + 1} textAnchor="middle" dominantBaseline="middle" fontSize={11} fill="#1d1d1f">
                      {n.label}
                    </text>
                    <text x={p.x} y={p.y + r + 12} textAnchor="middle" fontSize={10} fill="#6e6e73">
                      {n.weight}
                    </text>
                  </g>
                );
              })}
            </svg>
          </div>
        )}
      </Card>
    </div>
  );
}
