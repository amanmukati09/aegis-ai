"use client";

import {
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

const PALETTE = ["#0071e3", "#34c759", "#ff9500", "#ff3b30", "#af52de", "#5ac8fa"];

export function LineTrend({ data, xKey, yKey }: { data: Record<string, unknown>[]; xKey: string; yKey: string }) {
  return (
    <ResponsiveContainer width="100%" height={240}>
      <LineChart data={data} margin={{ top: 8, right: 12, left: -12, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#00000010" />
        <XAxis dataKey={xKey} tick={{ fontSize: 11, fill: "#6e6e73" }} />
        <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: "#6e6e73" }} />
        <Tooltip />
        <Line type="monotone" dataKey={yKey} stroke="#0071e3" strokeWidth={2} dot={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}

export function BarBreakdown({ data, xKey, yKey }: { data: Record<string, unknown>[]; xKey: string; yKey: string }) {
  return (
    <ResponsiveContainer width="100%" height={240}>
      <BarChart data={data} margin={{ top: 8, right: 12, left: -12, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#00000010" />
        <XAxis dataKey={xKey} tick={{ fontSize: 11, fill: "#6e6e73" }} />
        <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: "#6e6e73" }} />
        <Tooltip />
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
        <Pie data={data} dataKey={valueKey} nameKey={nameKey} innerRadius={55} outerRadius={90} paddingAngle={2}>
          {data.map((_, i) => (
            <Cell key={i} fill={PALETTE[i % PALETTE.length]} />
          ))}
        </Pie>
        <Tooltip />
      </PieChart>
    </ResponsiveContainer>
  );
}
