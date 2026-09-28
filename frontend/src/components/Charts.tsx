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
const tooltipStyle = {
  contentStyle: {
    background: "rgba(28,30,40,0.95)",
    border: "1px solid rgba(255,255,255,0.1)",
    borderRadius: 12,
    color: "#ededf0",
    fontSize: 12,
  },
};

export function AreaTrend({ data, xKey, yKey }: { data: Record<string, unknown>[]; xKey: string; yKey: string }) {
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
