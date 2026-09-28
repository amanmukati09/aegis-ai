"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { streamsApi, type Stream } from "@/lib/platform-api";
import { Button, Card, ErrorText, Field, Input } from "@/components/ui";

export default function StreamsPage() {
  const { token, isAdmin } = useAuth();
  const [streams, setStreams] = useState<Stream[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    try {
      setStreams(await streamsApi.list(token));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load");
    }
  }, [token]);

  useEffect(() => { load(); }, [load]);

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

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="text-2xl font-semibold tracking-tight">Streams</h1>
      <p className="mt-1 text-sm text-ink-soft">Register metric/log sources for monitoring.</p>
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
          <p className="p-6 text-sm text-ink-soft">No streams registered yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-black/5 text-left text-xs uppercase tracking-wide text-ink-soft">
                <th className="px-5 py-3 font-medium">Name</th>
                <th className="px-5 py-3 font-medium">Source</th>
                <th className="px-5 py-3 font-medium">Status</th>
                {isAdmin && <th className="px-5 py-3" />}
              </tr>
            </thead>
            <tbody>
              {streams.map((s) => (
                <tr key={s.id} className="border-b border-black/5 last:border-0">
                  <td className="px-5 py-3 font-medium">{s.name}<div className="text-xs text-ink-soft">{s.description}</div></td>
                  <td className="px-5 py-3">{s.sourceType}</td>
                  <td className="px-5 py-3">{s.status}</td>
                  {isAdmin && (
                    <td className="px-5 py-3 text-right">
                      <button onClick={() => token && streamsApi.remove(token, s.id).then(load)} className="text-red-600 hover:underline">Delete</button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
