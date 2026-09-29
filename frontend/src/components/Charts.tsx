"use client";

import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

const PALETTE = ["#7c7aff", "#389eff", "#34c759", "#ff9f0a", "#ff453a", "#bf5af2"];

const AXIS = { fontSize: 11, fill: "rgb(148 150 160)" };

// Theme-aware tooltip: reads the same CSS vars as the rest of the UI (--surface/--ink/
// --border) via rgb(var(...)) so it looks native in both light and dark mode instead of
// being hardcoded to one theme.
const tooltipStyle = {
  contentStyle: {
    background: "rgb(var(--surface) / 0.97)",
    border: "1px solid rgb(var(--border) / 0.12)",
    borderRadius: 12,
    color: "rgb(var(--ink))",
    fontSize: 12,
    boxShadow: "0 8px 30px rgb(0 0 0 / 0.12)",
  },
  labelStyle: { color: "rgb(var(--ink-soft))" },
};

function ChartEmpty({ height, label = "No data yet" }: { height: number; label?: string }) {
  return (
    <div
      style={{ height }}
      className="flex items-center justify-center rounded-xl border border-dashed border-line/15 text-sm text-ink-soft"
    >
      {label}
    </div>
  );
}

export function AreaTrend({ data, xKey, yKey }: { data: Record<string, unknown>[]; xKey: string; yKey: string }) {
  if (!data || data.length === 0) return <ChartEmpty height={260} />;
  return (
    <ResponsiveContainer width="100%" height={260}>
      <AreaChart data={data} margin={{ top: 8, right: 12, left: -12, bottom: 0 }}>
        <defs>
          <linearGradient id="areaFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#7c7aff" stopOpacity={0.5} />
            <stop offset="100%" stopColor="#7c7aff" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,150,160,0.12)" />
        <XAxis dataKey={xKey} tick={AXIS} />
        <YAxis allowDecimals={false} tick={AXIS} />
        <Tooltip {...tooltipStyle} />
        <Area type="monotone" dataKey={yKey} stroke="#7c7aff" strokeWidth={2} fill="url(#areaFill)" />
      </AreaChart>
    </ResponsiveContainer>
  );
}

export function LineTrend({ data, xKey, yKey }: { data: Record<string, unknown>[]; xKey: string; yKey: string }) {
  if (!data || data.length === 0) return <ChartEmpty height={240} />;
  return (
    <ResponsiveContainer width="100%" height={240}>
      <LineChart data={data} margin={{ top: 8, right: 12, left: -12, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,150,160,0.12)" />
        <XAxis dataKey={xKey} tick={AXIS} />
        <YAxis allowDecimals={false} tick={AXIS} />
        <Tooltip {...tooltipStyle} />
        <Line type="monotone" dataKey={yKey} stroke="#7c7aff" strokeWidth={2} dot={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}

export function BarBreakdown({ data, xKey, yKey }: { data: Record<string, unknown>[]; xKey: string; yKey: string }) {
  if (!data || data.length === 0) return <ChartEmpty height={240} />;
  return (
    <ResponsiveContainer width="100%" height={240}>
      <BarChart data={data} margin={{ top: 8, right: 12, left: -12, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,150,160,0.12)" />
        <XAxis dataKey={xKey} tick={AXIS} />
        <YAxis allowDecimals={false} tick={AXIS} />
        <Tooltip {...tooltipStyle} cursor={{ fill: "rgba(148,150,160,0.08)" }} />
        <Bar dataKey={yKey} radius={[6, 6, 0, 0]}>
          {data.map((_, i) => (
            <Cell key={i} fill={PALETTE[i % PALETTE.length]} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}

export function DonutBreakdown({ data, nameKey, valueKey }: { data: Record<string, unknown>[]; nameKey: string; valueKey: string }) {
  if (!data || data.length === 0) return <ChartEmpty height={240} />;
  return (
    <ResponsiveContainer width="100%" height={240}>
      <PieChart>
        <Pie data={data} dataKey={valueKey} nameKey={nameKey} innerRadius={55} outerRadius={90} paddingAngle={2} stroke="none">
          {data.map((_, i) => (
            <Cell key={i} fill={PALETTE[i % PALETTE.length]} />
          ))}
        </Pie>
        <Tooltip {...tooltipStyle} />
      </PieChart>
    </ResponsiveContainer>
  );
}
