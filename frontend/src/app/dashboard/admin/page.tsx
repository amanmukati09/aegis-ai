"use client";

import { useCallback, useEffect, useState } from "react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { useAuth } from "@/lib/auth-context";
import { adminApi, type AdminUser, type AuditEntry } from "@/lib/platform-api";
import { Card } from "@/components/ui";

function AdminInner() {
  const { token } = useAuth();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [metrics, setMetrics] = useState<Record<string, number>>({});

  const load = useCallback(async () => {
    if (!token) return;
    const [u, a, m] = await Promise.all([
      adminApi.users(token),
      adminApi.auditLogs(token),
      adminApi.metrics(token),
    ]);
    setUsers(u); setAudit(a); setMetrics(m);
  }, [token]);

  useEffect(() => { load(); }, [load]);

  return (
    <div className="mx-auto max-w-5xl">
      <h1 className="text-2xl font-semibold tracking-tight">Admin</h1>
      <p className="mt-1 text-sm text-ink-soft">Organization users, activity, and metrics.</p>

      <div className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Card><p className="text-sm text-ink-soft">Users</p><p className="mt-1 text-2xl font-semibold">{metrics.users ?? "—"}</p></Card>
        <Card><p className="text-sm text-ink-soft">Incidents</p><p className="mt-1 text-2xl font-semibold">{metrics.incidents ?? "—"}</p></Card>
      </div>

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
