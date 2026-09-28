"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { analyticsApi, type AskResult } from "@/lib/platform-api";
import { Button, Card, ErrorText, Input } from "@/components/ui";
import { BarBreakdown, LineTrend } from "@/components/Charts";

export default function AnalyticsPage() {
  const { token } = useAuth();
  const [presets, setPresets] = useState<string[]>([]);
  const [question, setQuestion] = useState("");
  const [result, setResult] = useState<AskResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (token) analyticsApi.presets(token).then(setPresets).catch(() => setPresets([]));
  }, [token]);

  const ask = useCallback(
    async (q: string) => {
      if (!token || !q.trim()) return;
      setLoading(true);
      setError(null);
      setResult(null);
      try {
        setResult(await analyticsApi.ask(token, q));
      } catch (e) {
        setError(e instanceof Error ? e.message : "Query failed");
      } finally {
        setLoading(false);
      }
    },
    [token]
  );

  const numericKey = result?.columns.find((c) => c !== result.columns[0]);

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="text-2xl font-semibold tracking-tight">Smart Analytics</h1>
      <p className="mt-1 text-sm text-ink-soft">Ask questions about your incidents in plain English.</p>

      <Card className="mt-6">
        <form onSubmit={(e) => { e.preventDefault(); ask(question); }} className="flex gap-2">
          <Input value={question} onChange={(e) => setQuestion(e.target.value)} placeholder="e.g. How many incidents by severity?" />
          <div className="w-28"><Button type="submit" disabled={loading}>{loading ? "…" : "Ask"}</Button></div>
        </form>
        <div className="mt-3 flex flex-wrap gap-2">
          {presets.map((p) => (
            <button key={p} onClick={() => { setQuestion(p); ask(p); }}
              className="rounded-full border border-black/10 px-3 py-1 text-xs text-ink-soft transition-colors hover:bg-surface-muted">
              {p}
            </button>
          ))}
        </div>
        <ErrorText message={error} />
      </Card>

      {result && (
        <div className="mt-6 space-y-4">
          {result.explanation && <p className="text-sm text-ink-soft">{result.explanation}</p>}

          {result.rows.length > 0 && numericKey && (result.chartType === "bar" || result.chartType === "line") && (
            <Card>
              {result.chartType === "line" ? (
                <LineTrend data={result.rows} xKey={result.columns[0]} yKey={numericKey} />
              ) : (
                <BarBreakdown data={result.rows} xKey={result.columns[0]} yKey={numericKey} />
              )}
            </Card>
          )}

          <Card className="p-0">
            {result.rows.length === 0 ? (
              <p className="p-6 text-sm text-ink-soft">No rows returned.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-black/5 text-left text-xs uppercase tracking-wide text-ink-soft">
                      {result.columns.map((c) => <th key={c} className="px-4 py-2 font-medium">{c}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {result.rows.map((row, i) => (
                      <tr key={i} className="border-b border-black/5 last:border-0">
                        {result.columns.map((c) => <td key={c} className="px-4 py-2">{String(row[c] ?? "")}</td>)}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <details className="text-xs text-ink-soft">
            <summary className="cursor-pointer">Generated SQL</summary>
            <code className="mt-1 block overflow-x-auto rounded-lg bg-ink/90 p-3 font-mono text-white">{result.sql}</code>
          </details>
        </div>
      )}
    </div>
  );
}
