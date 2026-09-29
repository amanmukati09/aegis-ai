"use client";

import { useCallback, useEffect, useState } from "react";
import { Activity, Lock, Trash2, UserMinus, Users } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import {
  workspacesApi,
  type OrgUserCandidate,
  type Workspace,
  type WorkspaceMember,
} from "@/lib/platform-api";
import { Button, Card, EmptyState, ErrorText, Field, Input, PageHeader } from "@/components/ui";

export default function WorkspacesPage() {
  const { token, isAdmin } = useAuth();
  const [items, setItems] = useState<Workspace[]>([]);
  const [name, setName] = useState("");
  const [selected, setSelected] = useState<Workspace | null>(null);
  const [members, setMembers] = useState<WorkspaceMember[]>([]);
  const [candidates, setCandidates] = useState<OrgUserCandidate[]>([]);
  const [candidateId, setCandidateId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!token) return;
    setItems(await workspacesApi.list(token));
  }, [token]);

  useEffect(() => { load(); }, [load]);

  const loadMembers = useCallback(async () => {
    if (!token || !selected) { setMembers([]); setCandidates([]); return; }
    try {
      const [m, c] = await Promise.all([
        workspacesApi.members(token, selected.id),
        isAdmin ? workspacesApi.candidates(token, selected.id) : Promise.resolve([]),
      ]);
      setMembers(m);
      setCandidates(c);
      setCandidateId(c[0]?.id ?? "");
    } catch {
      setMembers([]);
      setCandidates([]);
    }
  }, [token, selected, isAdmin]);

  useEffect(() => { loadMembers(); }, [loadMembers]);

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!token || !name.trim()) return;
    try { await workspacesApi.create(token, name.trim()); setName(""); load(); }
    catch (e) { setError(e instanceof Error ? e.message : "Failed"); }
  }

  async function onAddMember() {
    if (!token || !selected || !candidateId) return;
    setBusy(true);
    setError(null);
    try { await workspacesApi.addMember(token, selected.id, candidateId); await loadMembers(); }
    catch (e) { setError(e instanceof Error ? e.message : "Failed"); }
    finally { setBusy(false); }
  }

  async function onRemoveMember(m: WorkspaceMember) {
    if (!token || !selected) return;
    if (!confirm(`Remove ${m.email} from ${selected.name}? They will lose access to this workspace's incidents.`)) return;
    setBusy(true);
    setError(null);
    try { await workspacesApi.removeMember(token, selected.id, m.userId); await loadMembers(); }
    catch (e) { setError(e instanceof Error ? e.message : "Failed"); }
    finally { setBusy(false); }
  }

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title="Workspaces"
        subtitle="Restrict a set of incidents to only the team members who should see them."
        icon={<Activity className="h-6 w-6 text-accent" />}
      />
      <ErrorText message={error} />

      <Card className="mt-6 flex items-start gap-3 bg-surface-2/60">
        <Lock className="mt-0.5 h-4 w-4 flex-shrink-0 text-accent" />
        <p className="text-sm text-ink-soft">
          Incidents tagged to a workspace are only visible to that workspace&apos;s members
          (plus org admins). Incidents with no workspace stay visible to everyone in your
          organization, as before — workspaces are opt-in per incident.
        </p>
      </Card>

      {isAdmin && (
        <Card className="mt-4">
          <form onSubmit={onCreate} className="flex items-end gap-3">
            <div className="flex-1"><Field label="Workspace name"><Input value={name} onChange={(e) => setName(e.target.value)} required /></Field></div>
            <div className="w-32"><Button type="submit">Create</Button></div>
          </form>
        </Card>
      )}

      <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2">
        <Card className="p-0">
          {items.length === 0 ? (
            <EmptyState icon={<Activity className="h-8 w-8" />} title="No workspaces" />
          ) : (
            <ul>
              {items.map((w) => (
                <li
                  key={w.id}
                  className={"flex items-center justify-between border-b border-line/10 px-5 py-3 last:border-0 transition-colors " +
                    (selected?.id === w.id ? "bg-surface-2" : "hover:bg-surface-2/60")}
                >
                  <button onClick={() => setSelected(w)} className="text-left text-sm font-medium">{w.name}</button>
                  {isAdmin && (
                    <button
                      onClick={() => token && workspacesApi.remove(token, w.id).then(() => { setSelected(null); load(); })}
                      className="inline-flex items-center gap-1 text-xs text-red-500 hover:underline"
                    >
                      <Trash2 className="h-3 w-3" /> Delete
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card>
          {selected ? (
            <>
              <h2 className="mb-3 flex items-center gap-2 text-sm font-medium">
                <Users className="h-4 w-4 text-accent" /> Members of {selected.name}
              </h2>
              <ul className="space-y-1 text-sm">
                {members.map((m) => (
                  <li key={m.id} className="flex items-center justify-between gap-2">
                    <span className="truncate">{m.email}</span>
                    <span className="flex items-center gap-2 text-ink-soft">
                      {m.role}
                      {isAdmin && (
                        <button
                          onClick={() => onRemoveMember(m)}
                          disabled={busy}
                          title={`Remove ${m.email}`}
                          className="text-red-500 hover:text-red-600 disabled:opacity-50"
                        >
                          <UserMinus className="h-3.5 w-3.5" />
                        </button>
                      )}
                    </span>
                  </li>
                ))}
                {members.length === 0 && <li className="text-ink-soft">No members.</li>}
              </ul>
              {isAdmin && (
                <div className="mt-4 flex gap-2">
                  {candidates.length === 0 ? (
                    <p className="text-xs text-ink-soft">
                      Everyone in your organization is already a member of this workspace.
                    </p>
                  ) : (
                    <>
                      <select
                        value={candidateId}
                        onChange={(e) => setCandidateId(e.target.value)}
                        className="flex-1 rounded-md border border-line/20 bg-surface px-3 py-2 text-sm"
                      >
                        {candidates.map((c) => (
                          <option key={c.id} value={c.id}>{c.fullName} ({c.email})</option>
                        ))}
                      </select>
                      <div className="w-28"><Button onClick={onAddMember} disabled={busy}>Add</Button></div>
                    </>
                  )}
                </div>
              )}
            </>
          ) : (
            <p className="text-sm text-ink-soft">Select a workspace to view members.</p>
          )}
        </Card>
      </div>
    </div>
  );
}
