"use client";

import { useCallback, useEffect, useState } from "react";
import { KeyRound, ShieldCheck, Trash2 } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { apiKeysApi, type ApiKeyView, type CreatedKey } from "@/lib/platform-api";
import { Button, Card, EmptyState, ErrorText, Field, Input, PageHeader, Table, TableRow } from "@/components/ui";

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
      <PageHeader
        title="API Keys"
        subtitle="Programmatic access keys for your account."
        icon={<KeyRound className="h-6 w-6 text-accent" />}
      />
      <ErrorText message={error} />

      {created && (
        <Card className="mt-4 ring-1 ring-accent/30">
          <p className="flex items-center gap-2 text-sm font-medium">
            <ShieldCheck className="h-4 w-4 text-accent" /> Copy your key now — it won&apos;t be shown again:
          </p>
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
          <EmptyState icon={<KeyRound className="h-8 w-8" />} title="No API keys yet" />
        ) : (
          <Table columns={["Name", "Prefix", "Created", ""]}>
            {keys.map((k) => (
              <TableRow key={k.id}>
                <td className="px-5 py-3 font-medium">{k.name}</td>
                <td className="px-5 py-3 font-mono text-xs">{k.keyPrefix}…</td>
                <td className="px-5 py-3 text-ink-soft">{new Date(k.createdAt).toLocaleDateString()}</td>
                <td className="px-5 py-3 text-right">
                  <button
                    onClick={() => token && apiKeysApi.revoke(token, k.id).then(load)}
                    className="inline-flex items-center gap-1 text-red-500 hover:underline"
                  >
                    <Trash2 className="h-3.5 w-3.5" /> Revoke
                  </button>
                </td>
              </TableRow>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
