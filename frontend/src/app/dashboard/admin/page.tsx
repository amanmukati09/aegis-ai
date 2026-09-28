"use client";

import { useCallback, useEffect, useState } from "react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { useAuth } from "@/lib/auth-context";
import { adminApi, trackcApi, type AdminUser, type AuditEntry, type TrackCStatus } from "@/lib/platform-api";
import { Button, Card, ErrorText, Field, Input } from "@/components/ui";

function AdminInner() {
  const { token } = useAuth();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [metrics, setMetrics] = useState<Record<string, number>>({});
  const [channels, setChannels] = useState<string[]>([]);
  const [trackc, setTrackc] = useState<TrackCStatus | null>(null);
  const [invite, setInvite] = useState({ email: "", fullName: "", role: "member", tempPassword: "" });
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

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

  return (
    <div className="mx-auto max-w-5xl">
      <h1 className="text-2xl font-semibold tracking-tight">Admin</h1>
      <p className="mt-1 text-sm text-ink-soft">Organization users, activity, and metrics.</p>

      <div className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Card><p className="text-sm text-ink-soft">Users</p><p className="mt-1 text-2xl font-semibold">{metrics.users ?? "—"}</p></Card>
        <Card><p className="text-sm text-ink-soft">Incidents</p><p className="mt-1 text-2xl font-semibold">{metrics.incidents ?? "—"}</p></Card>
      </div>

      {msg && <p className="mt-4 rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{msg}</p>}
      <ErrorText message={error} />

      <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <h2 className="mb-3 text-sm font-medium">Invite member</h2>
          <form onSubmit={onInvite} className="space-y-3">
            <Field label="Email"><Input type="email" value={invite.email} onChange={(e) => setInvite({ ...invite, email: e.target.value })} required /></Field>
            <Field label="Full name"><Input value={invite.fullName} onChange={(e) => setInvite({ ...invite, fullName: e.target.value })} required /></Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="Role">
                <select value={invite.role} onChange={(e) => setInvite({ ...invite, role: e.target.value })}
                  className="h-11 w-full rounded-xl border border-black/10 bg-surface px-3 text-sm outline-none">
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
          <h2 className="mb-3 text-sm font-medium">Alert channels</h2>
          {channels.length === 0 ? (
            <p className="text-sm text-ink-soft">Only stdout is active. Add Slack/Teams/PagerDuty/SMTP keys to <code>.env</code> to enable more.</p>
          ) : (
            <ul className="flex flex-wrap gap-2">
              {channels.map((c) => <li key={c} className="rounded-full bg-surface-muted px-3 py-1 text-xs capitalize">{c}</li>)}
            </ul>
          )}
          <button onClick={onTestAlert} className="mt-4 text-sm text-accent hover:underline">Send test alert</button>
        </Card>
      </div>

      {trackc && (
        <Card className="mt-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-medium">Platform capabilities (Track C)</h2>
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

      <h2 className="mt-8 text-sm font-medium">Users</h2>
      <Card className="mt-2 p-0">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-black/5 text-left text-xs uppercase tracking-wide text-ink-soft">
              <th className="px-5 py-3 font-medium">Email</th>
              <th className="px-5 py-3 font-medium">Name</th>
              <th className="px-5 py-3 font-medium">Role</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} className="border-b border-black/5 last:border-0">
                <td className="px-5 py-3">{u.email}</td>
                <td className="px-5 py-3">{u.fullName}</td>
                <td className="px-5 py-3 capitalize">{u.role.replace("_", " ")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <h2 className="mt-8 text-sm font-medium">Audit log</h2>
      <Card className="mt-2 p-0">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-black/5 text-left text-xs uppercase tracking-wide text-ink-soft">
              <th className="px-5 py-3 font-medium">Action</th>
              <th className="px-5 py-3 font-medium">User</th>
              <th className="px-5 py-3 font-medium">Resource</th>
              <th className="px-5 py-3 font-medium">When</th>
            </tr>
          </thead>
          <tbody>
            {audit.map((a) => (
              <tr key={a.id} className="border-b border-black/5 last:border-0">
                <td className="px-5 py-3 font-medium">{a.action}</td>
                <td className="px-5 py-3 text-ink-soft">{a.userEmail ?? "—"}</td>
                <td className="px-5 py-3 text-ink-soft">{a.resourceType ?? "—"}</td>
                <td className="px-5 py-3 text-ink-soft">{new Date(a.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
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
