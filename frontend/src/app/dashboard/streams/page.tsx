"use client";

import { useCallback, useEffect, useState } from "react";
import { Boxes, ChevronDown, ChevronUp, Copy, Send, Trash2 } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { streamsApi, type Stream } from "@/lib/platform-api";
import { Button, Card, EmptyState, ErrorText, Field, Input, PageHeader, Table, TableRow } from "@/components/ui";

const GATEWAY_PUBLIC_URL = process.env.NEXT_PUBLIC_GATEWAY_PUBLIC_URL || "https://<your-gateway-host>";

export default function StreamsPage() {
  const { token, isAdmin } = useAuth();
  const [streams, setStreams] = useState<Stream[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    try {
      setStreams(await streamsApi.list(token));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load");
    }
  }, [token]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!expanded) return;
    const t = setInterval(load, 5000); // watch activity live while a connect panel is open
    return () => clearInterval(t);
  }, [expanded, load]);

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!token || !name.trim()) return;
    try {
      await streamsApi.create(token, name.trim(), description.trim() || undefined);
      setName(""); setDescription("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create");
    }
  }

  async function onSendTestEvent(id: string) {
    if (!token) return;
    setTestResult(null);
    try {
      const res = await streamsApi.pushEvents(token, id, [
        "2026-01-01T00:00:00Z ERROR test-source: connection refused to db:5432",
      ]);
      setTestResult(
        res.incidentCreated
          ? `Test event created incident ${res.incidentId?.slice(0, 8)} (severity: ${res.worstSeverity})`
          : `Test event received (severity: ${res.worstSeverity}, below the auto-incident threshold)`
      );
      load();
    } catch (e) {
      setTestResult(e instanceof Error ? e.message : "Test push failed");
    }
  }

  function curlFor(streamId: string) {
    return `curl -X POST ${GATEWAY_PUBLIC_URL}/api/streams/${streamId}/events \\
  -H "Authorization: Bearer <YOUR_API_KEY>" \\
  -H "Content-Type: application/json" \\
  -d '{"lines": ["2026-01-01T00:00:00Z ERROR my-service: connection refused"]}'`;
  }

  function copy(text: string) {
    navigator.clipboard?.writeText(text).catch(() => {});
  }

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title="Streams"
        subtitle="Register a log source, then push real log lines into it — anomalous batches become incidents automatically."
        icon={<Boxes className="h-6 w-6 text-accent" />}
      />
      <ErrorText message={error} />

      <Card className="mt-6">
        <form onSubmit={onCreate} className="flex items-end gap-3">
          <div className="flex-1"><Field label="Name"><Input value={name} onChange={(e) => setName(e.target.value)} required /></Field></div>
          <div className="flex-1"><Field label="Description"><Input value={description} onChange={(e) => setDescription(e.target.value)} /></Field></div>
          <div className="w-32"><Button type="submit">Register</Button></div>
        </form>
      </Card>

      <Card className="mt-4 p-0">
        {streams.length === 0 ? (
          <EmptyState icon={<Boxes className="h-8 w-8" />} title="No streams registered yet" />
        ) : (
          <Table columns={["Name", "Source", "Status", "Activity", ...(isAdmin ? [""] : [])]}>
            {streams.flatMap((s) => {
              const rows = [
                <TableRow key={s.id} onClick={() => setExpanded(expanded === s.id ? null : s.id)}>
                  <td className="px-5 py-3 font-medium">{s.name}<div className="text-xs text-ink-soft">{s.description}</div></td>
                  <td className="px-5 py-3">{s.sourceType}</td>
                  <td className="px-5 py-3">
                    <span className={"rounded-full px-2 py-0.5 text-xs font-medium " +
                      (s.status === "active" ? "bg-emerald-500/15 text-emerald-500" : "bg-ink-soft/15 text-ink-soft")}>
                      {s.status}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-ink-soft">
                    {s.eventCount > 0
                      ? `${s.eventCount.toLocaleString()} lines · last ${new Date(s.lastEventAt!).toLocaleString()}`
                      : "No activity yet"}
                  </td>
                  {isAdmin && (
                    <td className="px-5 py-3 text-right">
                      <div className="flex items-center justify-end gap-3">
                        <button className="text-ink-soft hover:text-accent">
                          {expanded === s.id ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                        </button>
                        <button
                          onClick={(e) => { e.stopPropagation(); token && streamsApi.remove(token, s.id).then(load); }}
                          className="inline-flex items-center gap-1 text-red-500 hover:underline"
                        >
                          <Trash2 className="h-3.5 w-3.5" /> Delete
                        </button>
                      </div>
                    </td>
                  )}
                </TableRow>,
              ];
              if (expanded === s.id) {
                rows.push(
                  <tr key={s.id + "-panel"} className="border-b border-line/10">
                    <td colSpan={isAdmin ? 5 : 4} className="bg-surface-2 px-5 py-4">
                      <p className="mb-2 text-xs font-medium uppercase tracking-wide text-ink-soft">
                        Connect a real log source
                      </p>
                      <p className="mb-2 text-xs text-ink-soft">
                        Create an <a href="/dashboard/api-keys" className="text-accent hover:underline">API key</a>,
                        then have your log shipper / agent / cron job POST batches of lines here — no extra
                        infrastructure required:
                      </p>
                      <div className="relative">
                        <pre className="overflow-x-auto rounded-lg bg-ink/90 p-3 font-mono text-xs text-white">
                          {curlFor(s.id)}
                        </pre>
                        <button
                          onClick={() => copy(curlFor(s.id))}
                          className="absolute right-2 top-2 rounded-md bg-white/10 p-1.5 text-white hover:bg-white/20"
                          title="Copy"
                        >
                          <Copy className="h-3.5 w-3.5" />
                        </button>
                      </div>
                      <button
                        onClick={() => onSendTestEvent(s.id)}
                        className="btn-ghost mt-3 flex h-8 items-center gap-2 px-3 text-xs"
                      >
                        <Send className="h-3.5 w-3.5" /> Send a test event
                      </button>
                      {testResult && <p className="mt-2 text-xs text-ink-soft">{testResult}</p>}
                    </td>
                  </tr>
                );
              }
              return rows;
            })}
          </Table>
        )}
      </Card>
    </div>
  );
}
