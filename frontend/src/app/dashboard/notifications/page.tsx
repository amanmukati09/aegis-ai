"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { notificationsApi, type Notification } from "@/lib/platform-api";
import { Card } from "@/components/ui";

export default function NotificationsPage() {
  const { token } = useAuth();
  const [items, setItems] = useState<Notification[]>([]);

  const load = useCallback(async () => {
    if (!token) return;
    const res = await notificationsApi.list(token);
    setItems(res.notifications);
  }, [token]);

  useEffect(() => { load(); }, [load]);

  async function onMarkRead() {
    if (!token) return;
    await notificationsApi.markRead(token);
    load();
  }

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Notifications</h1>
        <button onClick={onMarkRead} className="text-sm text-accent hover:underline">Mark all read</button>
      </div>
      <Card className="p-0">
        {items.length === 0 ? (
          <p className="p-6 text-sm text-ink-soft">No notifications.</p>
        ) : (
          <ul>
            {items.map((n) => (
              <li key={n.id} className="flex items-start gap-3 border-b border-black/5 px-5 py-3 last:border-0">
                <span className={"mt-1.5 h-2 w-2 shrink-0 rounded-full " + (n.read ? "bg-transparent" : "bg-accent")} />
                <div>
                  <p className="text-sm font-medium">{n.title}</p>
                  {n.message && <p className="text-sm text-ink-soft">{n.message}</p>}
                  <p className="mt-0.5 text-xs text-ink-soft">{new Date(n.createdAt).toLocaleString()}</p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
