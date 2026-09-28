"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { useAuth } from "@/lib/auth-context";

const NAV = [
  { href: "/dashboard", label: "Overview" },
  { href: "/dashboard/diagnosis", label: "AI Diagnosis" },
  { href: "/dashboard/incidents", label: "Incidents" },
  { href: "/dashboard/copilot", label: "AI Copilot" },
  { href: "/dashboard/analytics", label: "Smart Analytics" },
  { href: "/dashboard/dependency", label: "Dependency Map" },
  { href: "/dashboard/live", label: "Live Monitor" },
  { href: "/dashboard/bulk", label: "Bulk Analysis" },
  { href: "/dashboard/streams", label: "Streams" },
  { href: "/dashboard/notifications", label: "Notifications" },
  { href: "/dashboard/workspaces", label: "Workspaces" },
  { href: "/dashboard/api-keys", label: "API Keys" },
];

const ADMIN_NAV = [{ href: "/dashboard/admin", label: "Admin" }];

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const { user, logout, isAdmin } = useAuth();
  const pathname = usePathname();
  const [mobileOpen, setMobileOpen] = useState(false);

  // Close the mobile drawer on navigation.
  useEffect(() => {
    setMobileOpen(false);
  }, [pathname]);

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

  const navContent = (
    <>
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
        <p className="truncate font-medium text-ink">{user?.email}</p>
        <p className="mt-0.5 capitalize">{user?.role?.replace("_", " ")}</p>
        <button onClick={logout} className="mt-2 text-accent hover:underline">Sign out</button>
      </div>
    </>
  );

  return (
    <ProtectedRoute>
      <div className="flex min-h-screen">
        {/* Desktop sidebar */}
        <aside className="hidden w-60 shrink-0 flex-col border-r border-black/5 bg-surface p-4 md:flex">
          {navContent}
        </aside>

        {/* Mobile drawer */}
        {mobileOpen && (
          <div className="fixed inset-0 z-50 flex md:hidden">
            <div className="absolute inset-0 bg-black/30" onClick={() => setMobileOpen(false)} />
            <aside className="relative flex w-64 flex-col bg-surface p-4 shadow-card">{navContent}</aside>
          </div>
        )}

        <div className="flex flex-1 flex-col">
          {/* Mobile top bar */}
          <header className="flex items-center gap-3 border-b border-black/5 bg-surface px-4 py-3 md:hidden">
            <button onClick={() => setMobileOpen(true)} aria-label="Open menu" className="text-ink">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="3" y1="6" x2="21" y2="6" /><line x1="3" y1="12" x2="21" y2="12" /><line x1="3" y1="18" x2="21" y2="18" />
              </svg>
            </button>
            <span className="text-base font-semibold tracking-tight">AegisAI</span>
          </header>
          <main className="flex-1 px-4 py-6 sm:px-6 sm:py-8">{children}</main>
        </div>
      </div>
    </ProtectedRoute>
  );
}
