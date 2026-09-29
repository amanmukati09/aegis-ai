"use client";

import { useCallback, useEffect, useState } from "react";
import {
  AlertTriangle, Bell, CheckCircle2, KeyRound, Layers, Power, Shield, Trash2, UserPlus, Users,
} from "lucide-react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { useAuth } from "@/lib/auth-context";
import { adminApi, trackcApi, type AdminUser, type AuditEntry, type TrackCStatus } from "@/lib/platform-api";
import { Button, Card, ErrorText, Field, Input, PageHeader, Table, TableRow } from "@/components/ui";
import { StatCard } from "@/components/StatCard";

function AdminInner() {
  const { token, user } = useAuth();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [metrics, setMetrics] = useState<Record<string, number>>({});
  const [channels, setChannels] = useState<string[]>([]);
  const [trackc, setTrackc] = useState<TrackCStatus | null>(null);
  const [invite, setInvite] = useState({ email: "", fullName: "", role: "member", tempPassword: "" });
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyUserId, setBusyUserId] = useState<string | null>(null);
  const [tempPasswordFor, setTempPasswordFor] = useState<{ email: string; value: string } | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    const [u, a, m, c, tc] = await Promise.all([
      adminApi.users(token),
      adminApi.auditLogs(token),
      adminApi.metrics(token),
      adminApi.alertStatus(token).catch(() => ({ configuredChannels: [] })),
      trackcApi.status(token).catch(() => null),
    ]);
    setUsers(u); setAudit(a); setMetrics(m); setChannels(c.configuredChannels); setTrackc(tc);
  }, [token]);

  useEffect(() => { load(); }, [load]);

  async function onInvite(e: React.FormEvent) {
    e.preventDefault();
    if (!token) return;
    setError(null); setMsg(null);
    try {
      await adminApi.invite(token, invite);
      setMsg(`Invited ${invite.email}`);
      setInvite({ email: "", fullName: "", role: "member", tempPassword: "" });
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Invite failed");
    }
  }

  async function onTestAlert() {
    if (!token) return;
    const res = await adminApi.alertTest(token);
    setMsg(`Test alert sent to: ${res.channels.join(", ") || "stdout"}`);
  }

  async function withRow(id: string, fn: () => Promise<void>) {
    if (!token) return;
    setBusyUserId(id);
    setError(null);
    setMsg(null);
    try {
      await fn();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Action failed");
    } finally {
      setBusyUserId(null);
    }
  }

  async function onToggleStatus(u: AdminUser) {
    if (!token) return;
    await withRow(u.id, async () => {
      await adminApi.setStatus(token, u.id, !u.active);
      setMsg(`${u.email} ${u.active ? "deactivated" : "reactivated"}.`);
      await load();
    });
  }

  async function onChangeRole(u: AdminUser, role: string) {
    if (!token || role === u.role) return;
    await withRow(u.id, async () => {
      await adminApi.setRole(token, u.id, role);
      setMsg(`${u.email} is now ${role.replace("_", " ")}.`);
      await load();
    });
  }

  async function onResetPassword(u: AdminUser) {
    if (!token) return;
    if (!confirm(`Reset the password for ${u.email}? They'll need the new temporary password to log in.`)) return;
    await withRow(u.id, async () => {
      const res = await adminApi.resetPassword(token, u.id);
      setTempPasswordFor({ email: u.email, value: res.tempPassword });
    });
  }

  async function onDeleteUser(u: AdminUser) {
    if (!token) return;
    if (!confirm(`Permanently delete ${u.email}? This cannot be undone.`)) return;
    await withRow(u.id, async () => {
      await adminApi.deleteUser(token, u.id);
      setMsg(`${u.email} deleted.`);
      await load();
    });
  }

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader
        title="Admin"
        subtitle="Organization users, activity, and metrics."
        icon={<Shield className="h-6 w-6 text-accent" />}
      />

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <StatCard label="Users" value={metrics.users ?? "—"} icon={<Users className="h-4 w-4" />} />
        <StatCard label="Incidents" value={metrics.incidents ?? "—"} icon={<AlertTriangle className="h-4 w-4" />} />
      </div>

      {msg && (
        <p className="mt-4 flex items-center gap-2 rounded-lg bg-emerald-500/10 px-3 py-2 text-sm text-emerald-500">
          <CheckCircle2 className="h-4 w-4" /> {msg}
        </p>
      )}
      <ErrorText message={error} />

      <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <h2 className="mb-3 flex items-center gap-2 text-sm font-medium">
            <UserPlus className="h-4 w-4 text-accent" /> Invite member
          </h2>
          <form onSubmit={onInvite} className="space-y-3">
            <Field label="Email"><Input type="email" value={invite.email} onChange={(e) => setInvite({ ...invite, email: e.target.value })} required /></Field>
            <Field label="Full name"><Input value={invite.fullName} onChange={(e) => setInvite({ ...invite, fullName: e.target.value })} required /></Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="Role">
                <select value={invite.role} onChange={(e) => setInvite({ ...invite, role: e.target.value })} className="input h-11">
                  <option value="member">member</option>
                  <option value="org_admin">org_admin</option>
                </select>
              </Field>
              <Field label="Temp password"><Input type="text" value={invite.tempPassword} onChange={(e) => setInvite({ ...invite, tempPassword: e.target.value })} required /></Field>
            </div>
            <Button type="submit">Invite</Button>
          </form>
        </Card>
        <Card>
          <h2 className="mb-3 flex items-center gap-2 text-sm font-medium">
            <Bell className="h-4 w-4 text-accent" /> Alert channels
          </h2>
          {channels.length === 0 ? (
            <p className="text-sm text-ink-soft">Only stdout is active. Add Slack/Teams/PagerDuty/SMTP keys to <code>.env</code> to enable more.</p>
          ) : (
            <ul className="flex flex-wrap gap-2">
              {channels.map((c) => <li key={c} className="rounded-full bg-surface-2 px-3 py-1 text-xs capitalize">{c}</li>)}
            </ul>
          )}
          <button onClick={onTestAlert} className="mt-4 text-sm text-accent hover:underline">Send test alert</button>
        </Card>
      </div>

      {trackc && (
        <Card className="mt-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="flex items-center gap-2 text-sm font-medium">
              <Layers className="h-4 w-4 text-accent" /> Platform capabilities (Track C)
            </h2>
            <span className={"rounded-full px-2 py-0.5 text-xs font-medium " +
              (trackc.mode === "extended" ? "bg-emerald-500/15 text-emerald-500" : "bg-surface-2 text-ink-soft")}>
              {trackc.mode} mode
            </span>
          </div>
          <div className="grid gap-2 sm:grid-cols-3">
            {trackc.capabilities.map((c) => (
              <div key={c.key} className="rounded-xl border border-line/10 p-3">
                <div className="flex items-center justify-between">
                  <p className="text-sm font-medium">{c.label}</p>
                  <span className={"h-2 w-2 rounded-full " + (c.active ? "bg-emerald-500" : "bg-ink-soft/30")} />
                </div>
                <p className="mt-1 text-xs text-ink-soft">{c.status}</p>
              </div>
            ))}
          </div>
          <p className="mt-3 text-xs text-ink-soft">
            Dormant on this deployment. Activate on a capable host — see <code>LAPTOP_SETUP.md</code>.
          </p>
        </Card>
      )}

      <h2 className="mt-8 flex items-center gap-2 text-sm font-medium">
        <Users className="h-4 w-4 text-accent" /> Users
      </h2>

      {tempPasswordFor && (
        <Card className="mt-2 ring-1 ring-accent/30">
          <p className="text-sm font-medium">
            New temporary password for <span className="text-accent">{tempPasswordFor.email}</span> — copy it now,
            it won&apos;t be shown again:
          </p>
          <code className="mt-2 block overflow-x-auto rounded-lg bg-ink/90 p-3 font-mono text-xs text-white">
            {tempPasswordFor.value}
          </code>
          <button onClick={() => setTempPasswordFor(null)} className="mt-2 text-sm text-accent hover:underline">Done</button>
        </Card>
      )}

      <Card className="mt-2 p-0">
        <Table columns={["Email", "Name", "Role", "Status", "Actions"]}>
          {users.map((u) => {
            const isSelf = u.id === user?.userId;
            const busy = busyUserId === u.id;
            return (
              <TableRow key={u.id}>
                <td className="px-5 py-3">{u.email}{isSelf && <span className="ml-1.5 text-xs text-ink-soft">(you)</span>}</td>
                <td className="px-5 py-3">{u.fullName}</td>
                <td className="px-5 py-3">
                  <select
                    value={u.role}
                    disabled={busy}
                    onChange={(e) => onChangeRole(u, e.target.value)}
                    className="input h-8 w-auto px-2 text-xs capitalize"
                  >
                    <option value="member">member</option>
                    <option value="org_admin">org admin</option>
                    <option value="super_admin">super admin</option>
                  </select>
                </td>
                <td className="px-5 py-3">
                  <span className={"rounded-full px-2 py-0.5 text-xs font-medium " +
                    (u.active ? "bg-emerald-500/15 text-emerald-500" : "bg-ink-soft/15 text-ink-soft")}>
                    {u.active ? "active" : "deactivated"}
                  </span>
                </td>
                <td className="px-5 py-3">
                  <div className="flex items-center gap-3 text-ink-soft">
                    <button
                      onClick={() => onToggleStatus(u)}
                      disabled={busy || isSelf}
                      title={isSelf ? "You can't deactivate yourself" : u.active ? "Deactivate" : "Reactivate"}
                      className="hover:text-accent disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      <Power className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => onResetPassword(u)}
                      disabled={busy}
                      title="Reset password"
                      className="hover:text-accent disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      <KeyRound className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => onDeleteUser(u)}
                      disabled={busy || isSelf}
                      title={isSelf ? "You can't delete yourself" : "Delete user"}
                      className="hover:text-red-500 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                </td>
              </TableRow>
            );
          })}
        </Table>
      </Card>

      <h2 className="mt-8 flex items-center gap-2 text-sm font-medium">
        <Layers className="h-4 w-4 text-accent" /> Audit log
      </h2>
      <Card className="mt-2 p-0">
        <Table columns={["Action", "User", "Resource", "When"]}>
          {audit.map((a) => (
            <TableRow key={a.id}>
              <td className="px-5 py-3 font-medium">{a.action}</td>
              <td className="px-5 py-3 text-ink-soft">{a.userEmail ?? "—"}</td>
              <td className="px-5 py-3 text-ink-soft">{a.resourceType ?? "—"}</td>
              <td className="px-5 py-3 text-ink-soft">{new Date(a.createdAt).toLocaleString()}</td>
            </TableRow>
          ))}
        </Table>
      </Card>
    </div>
  );
}

export default function AdminPage() {
  return (
    <ProtectedRoute adminOnly>
      <AdminInner />
    </ProtectedRoute>
  );
}
