"use client";

import { useCallback, useEffect, useState } from "react";
import { motion } from "framer-motion";
import { Bell, CheckCheck } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { notificationsApi, type Notification } from "@/lib/platform-api";
import { Card, EmptyState, PageHeader } from "@/components/ui";

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

  const unread = items.filter((n) => !n.read).length;

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Notifications"
        subtitle={unread > 0 ? `${unread} unread` : "You're all caught up."}
        icon={<Bell className="h-6 w-6 text-accent" />}
        actions={
          unread > 0 && (
            <button onClick={onMarkRead} className="btn-ghost flex h-9 items-center gap-2 px-3 text-sm">
              <CheckCheck className="h-4 w-4" /> Mark all read
            </button>
          )
        }
      />
      <Card className="p-0">
        {items.length === 0 ? (
          <EmptyState icon={<Bell className="h-8 w-8" />} title="No notifications" />
        ) : (
          <ul>
            {items.map((n, i) => (
              <motion.li
                key={n.id}
                initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.02 }}
                className="flex items-start gap-3 border-b border-line/10 px-5 py-3 last:border-0"
              >
                <span className={"mt-1.5 h-2 w-2 shrink-0 rounded-full " + (n.read ? "bg-transparent" : "bg-accent")} />
                <div>
                  <p className="text-sm font-medium">{n.title}</p>
                  {n.message && <p className="text-sm text-ink-soft">{n.message}</p>}
                  <p className="mt-0.5 text-xs text-ink-soft">{new Date(n.createdAt).toLocaleString()}</p>
                </div>
              </motion.li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
