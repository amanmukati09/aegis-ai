"use client";

import { useCallback, useEffect, useState } from "react";
import { BarChart3, ChevronDown, Search, Send } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { analyticsApi, type AskResult } from "@/lib/platform-api";
import { Button, Card, ErrorText, Input, PageHeader, Table, TableRow } from "@/components/ui";
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
      <PageHeader
        title="Smart Analytics"
        subtitle="Ask questions about your incidents in plain English."
        icon={<BarChart3 className="h-6 w-6 text-accent" />}
      />

      <Card>
        <form onSubmit={(e) => { e.preventDefault(); ask(question); }} className="flex gap-2">
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-soft" />
            <Input
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder="e.g. How many incidents by severity?"
              className="pl-9"
            />
          </div>
          <div className="w-28">
            <Button type="submit" disabled={loading} className="flex items-center justify-center gap-1.5">
              {loading ? "…" : <><Send className="h-3.5 w-3.5" /> Ask</>}
            </Button>
          </div>
        </form>
        <div className="mt-3 flex flex-wrap gap-2">
          {presets.map((p) => (
            <button key={p} onClick={() => { setQuestion(p); ask(p); }}
              className="rounded-full border border-line/10 px-3 py-1 text-xs text-ink-soft transition-colors hover:bg-surface-2">
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
              <Table columns={result.columns}>
                {result.rows.map((row, i) => (
                  <TableRow key={i}>
                    {result.columns.map((c) => <td key={c} className="px-4 py-2">{String(row[c] ?? "")}</td>)}
                  </TableRow>
                ))}
              </Table>
            )}
          </Card>

          <details className="group text-xs text-ink-soft">
            <summary className="flex cursor-pointer items-center gap-1">
              <ChevronDown className="h-3.5 w-3.5 transition-transform group-open:rotate-180" /> Generated SQL
            </summary>
            <code className="mt-1 block overflow-x-auto rounded-lg bg-ink/90 p-3 font-mono text-white">{result.sql}</code>
          </details>
        </div>
      )}
    </div>
  );
}
