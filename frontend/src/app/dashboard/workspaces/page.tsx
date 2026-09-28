"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { workspacesApi, type Workspace, type WorkspaceMember } from "@/lib/platform-api";
import { Button, Card, ErrorText, Field, Input } from "@/components/ui";

export default function WorkspacesPage() {
  const { token, isAdmin } = useAuth();
  const [items, setItems] = useState<Workspace[]>([]);
  const [name, setName] = useState("");
  const [selected, setSelected] = useState<Workspace | null>(null);
  const [members, setMembers] = useState<WorkspaceMember[]>([]);
  const [memberId, setMemberId] = useState("");
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    setItems(await workspacesApi.list(token));
  }, [token]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!token || !selected) { setMembers([]); return; }
    workspacesApi.members(token, selected.id).then(setMembers).catch(() => setMembers([]));
  }, [token, selected]);

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!token || !name.trim()) return;
    try { await workspacesApi.create(token, name.trim()); setName(""); load(); }
    catch (e) { setError(e instanceof Error ? e.message : "Failed"); }
  }

  async function onAddMember() {
    if (!token || !selected || !memberId.trim()) return;
    try { await workspacesApi.addMember(token, selected.id, memberId.trim()); setMemberId("");
      setMembers(await workspacesApi.members(token, selected.id)); }
    catch (e) { setError(e instanceof Error ? e.message : "Failed"); }
  }

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="text-2xl font-semibold tracking-tight">Workspaces</h1>
      <p className="mt-1 text-sm text-ink-soft">Group work and members within your organization.</p>
      <ErrorText message={error} />

      {isAdmin && (
        <Card className="mt-6">
          <form onSubmit={onCreate} className="flex items-end gap-3">
            <div className="flex-1"><Field label="Workspace name"><Input value={name} onChange={(e) => setName(e.target.value)} required /></Field></div>
            <div className="w-32"><Button type="submit">Create</Button></div>
          </form>
        </Card>
      )}

      <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2">
        <Card className="p-0">
          {items.length === 0 ? <p className="p-6 text-sm text-ink-soft">No workspaces.</p> : (
            <ul>
              {items.map((w) => (
                <li key={w.id} className={"flex items-center justify-between border-b border-black/5 px-5 py-3 last:border-0 " + (selected?.id === w.id ? "bg-surface-muted" : "")}>
                  <button onClick={() => setSelected(w)} className="text-left text-sm font-medium">{w.name}</button>
                  {isAdmin && <button onClick={() => token && workspacesApi.remove(token, w.id).then(() => { setSelected(null); load(); })} className="text-xs text-red-600 hover:underline">Delete</button>}
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card>
          {selected ? (
            <>
              <h2 className="mb-3 text-sm font-medium">Members of {selected.name}</h2>
              <ul className="space-y-1 text-sm">
                {members.map((m) => <li key={m.id} className="flex justify-between"><span className="font-mono text-xs">{m.userId.slice(0, 8)}…</span><span className="text-ink-soft">{m.role}</span></li>)}
                {members.length === 0 && <li className="text-ink-soft">No members.</li>}
              </ul>
              {isAdmin && (
                <div className="mt-4 flex gap-2">
                  <Input value={memberId} onChange={(e) => setMemberId(e.target.value)} placeholder="User ID" />
                  <div className="w-28"><Button onClick={onAddMember}>Add</Button></div>
                </div>
              )}
            </>
          ) : <p className="text-sm text-ink-soft">Select a workspace to view members.</p>}
        </Card>
      </div>
    </div>
  );
}
