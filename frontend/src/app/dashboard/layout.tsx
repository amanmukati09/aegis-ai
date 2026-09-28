"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { useAuth } from "@/lib/auth-context";

const NAV = [
  { href: "/dashboard", label: "Overview" },
  { href: "/dashboard/diagnosis", label: "AI Diagnosis" },
  { href: "/dashboard/incidents", label: "Incidents" },
  { href: "/dashboard/copilot", label: "AI Copilot" },
  { href: "/dashboard/streams", label: "Streams" },
  { href: "/dashboard/notifications", label: "Notifications" },
  { href: "/dashboard/workspaces", label: "Workspaces" },
  { href: "/dashboard/api-keys", label: "API Keys" },
];

const ADMIN_NAV = [{ href: "/dashboard/admin", label: "Admin" }];

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const { user, logout, isAdmin } = useAuth();
  const pathname = usePathname();

  const renderLink = (item: { href: string; label: string }) => {
    const active = pathname === item.href;
    return (
      <Link
        key={item.href}
        href={item.href}
        className={
          "block rounded-xl px-3 py-2 text-sm transition-colors " +
          (active ? "bg-surface-muted font-medium text-ink" : "text-ink-soft hover:bg-surface-muted")
        }
      >
        {item.label}
      </Link>
    );
  };

  return (
    <ProtectedRoute>
      <div className="flex min-h-screen">
        <aside className="hidden w-60 shrink-0 flex-col border-r border-black/5 bg-surface p-4 md:flex">
          <div className="px-2 py-3 text-lg font-semibold tracking-tight">AegisAI</div>
          <nav className="mt-4 space-y-1">
            {NAV.map(renderLink)}
            {isAdmin && (
              <>
                <div className="px-3 pb-1 pt-4 text-xs uppercase tracking-wide text-ink-soft">Admin</div>
                {ADMIN_NAV.map(renderLink)}
              </>
            )}
          </nav>
          <div className="mt-auto rounded-xl bg-surface-muted p-3 text-xs text-ink-soft">
            <p className="font-medium text-ink">{user?.email}</p>
            <p className="mt-0.5 capitalize">{user?.role?.replace("_", " ")}</p>
            <button onClick={logout} className="mt-2 text-accent hover:underline">
              Sign out
            </button>
          </div>
        </aside>
        <main className="flex-1 px-6 py-8">{children}</main>
      </div>
    </ProtectedRoute>
  );
}
