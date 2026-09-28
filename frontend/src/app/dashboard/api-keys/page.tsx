"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { apiKeysApi, type ApiKeyView, type CreatedKey } from "@/lib/platform-api";
import { Button, Card, ErrorText, Field, Input } from "@/components/ui";

export default function ApiKeysPage() {
  const { token } = useAuth();
  const [keys, setKeys] = useState<ApiKeyView[]>([]);
  const [name, setName] = useState("");
  const [created, setCreated] = useState<CreatedKey | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    setKeys(await apiKeysApi.list(token));
  }, [token]);

  useEffect(() => { load(); }, [load]);

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!token || !name.trim()) return;
    try {
      setCreated(await apiKeysApi.create(token, name.trim()));
      setName("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create");
    }
  }

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="text-2xl font-semibold tracking-tight">API Keys</h1>
      <p className="mt-1 text-sm text-ink-soft">Programmatic access keys for your account.</p>
      <ErrorText message={error} />

      {created && (
        <Card className="mt-4 border border-accent/30">
          <p className="text-sm font-medium">Copy your key now — it won&apos;t be shown again:</p>
          <code className="mt-2 block overflow-x-auto rounded-lg bg-ink/90 p-3 font-mono text-xs text-white">{created.key}</code>
          <button onClick={() => setCreated(null)} className="mt-2 text-sm text-accent hover:underline">Done</button>
        </Card>
      )}

      <Card className="mt-4">
        <form onSubmit={onCreate} className="flex items-end gap-3">
          <div className="flex-1"><Field label="Key name"><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. CI pipeline" required /></Field></div>
          <div className="w-32"><Button type="submit">Create</Button></div>
        </form>
      </Card>

      <Card className="mt-4 p-0">
        {keys.length === 0 ? (
          <p className="p-6 text-sm text-ink-soft">No API keys yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-black/5 text-left text-xs uppercase tracking-wide text-ink-soft">
                <th className="px-5 py-3 font-medium">Name</th>
                <th className="px-5 py-3 font-medium">Prefix</th>
                <th className="px-5 py-3 font-medium">Created</th>
                <th className="px-5 py-3" />
              </tr>
            </thead>
            <tbody>
              {keys.map((k) => (
                <tr key={k.id} className="border-b border-black/5 last:border-0">
                  <td className="px-5 py-3 font-medium">{k.name}</td>
                  <td className="px-5 py-3 font-mono text-xs">{k.keyPrefix}…</td>
                  <td className="px-5 py-3 text-ink-soft">{new Date(k.createdAt).toLocaleDateString()}</td>
                  <td className="px-5 py-3 text-right">
                    <button onClick={() => token && apiKeysApi.revoke(token, k.id).then(load)} className="text-red-600 hover:underline">Revoke</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
