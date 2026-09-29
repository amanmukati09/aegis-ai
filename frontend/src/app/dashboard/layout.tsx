"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import {
  Activity, AlertTriangle, BarChart3, Bell, BookOpen, Bot, Boxes, Brain, FileSearch, Gauge,
  KeyRound, LayoutDashboard, Network, Radio, Shield, Stethoscope, Menu, X,
} from "lucide-react";
import { ProtectedRoute } from "@/components/ProtectedRoute";
import { ThemeToggle } from "@/components/ThemeToggle";
import { Logo } from "@/components/Logo";
import { useAuth } from "@/lib/auth-context";

// Grouped like a modern SaaS console (Linear/Vercel-style sidebar sections) instead of one
// long flat list — makes 16 destinations scannable instead of overwhelming.
const NAV_GROUPS: { label: string; items: { href: string; label: string; icon: typeof LayoutDashboard }[] }[] = [
  {
    label: "Overview",
    items: [
      { href: "/dashboard", label: "Overview", icon: LayoutDashboard },
      { href: "/dashboard/incidents", label: "Incidents", icon: AlertTriangle },
      { href: "/dashboard/live", label: "Live Monitor", icon: Radio },
    ],
  },
  {
    label: "Intelligence",
    items: [
      { href: "/dashboard/diagnosis", label: "AI Diagnosis", icon: Stethoscope },
      { href: "/dashboard/copilot", label: "AI Copilot", icon: Bot },
      { href: "/dashboard/triage", label: "RL Triage", icon: Brain },
      { href: "/dashboard/knowledge-base", label: "Knowledge Base", icon: BookOpen },
    ],
  },
  {
    label: "Analytics",
    items: [
      { href: "/dashboard/analytics", label: "Smart Analytics", icon: BarChart3 },
      { href: "/dashboard/insights", label: "Insights", icon: Gauge },
      { href: "/dashboard/dependency", label: "Dependency Map", icon: Network },
      { href: "/dashboard/bulk", label: "Bulk Analysis", icon: FileSearch },
    ],
  },
  {
    label: "Platform",
    items: [
      { href: "/dashboard/streams", label: "Streams", icon: Boxes },
      { href: "/dashboard/notifications", label: "Notifications", icon: Bell },
      { href: "/dashboard/workspaces", label: "Workspaces", icon: Activity },
      { href: "/dashboard/api-keys", label: "API Keys", icon: KeyRound },
    ],
  },
];

const ADMIN_NAV = [{ href: "/dashboard/admin", label: "Admin", icon: Shield }];
const ALL_NAV = NAV_GROUPS.flatMap((g) => g.items);

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const { user, logout, isAdmin } = useAuth();
  const pathname = usePathname();
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => setMobileOpen(false), [pathname]);

  const renderLink = ({ href, label, icon: Icon }: { href: string; label: string; icon: typeof LayoutDashboard }) => {
    const active = pathname === href;
    return (
      <Link
        key={href}
        href={href}
        className={
          "group relative flex items-center gap-3 rounded-xl px-3 py-2 text-sm transition-all " +
          (active ? "text-white" : "text-ink-soft hover:bg-surface-2 hover:text-ink")
        }
      >
        {active && (
          <motion.span
            layoutId="nav-active"
            className="absolute inset-0 rounded-xl bg-accent-gradient shadow-glow"
            transition={{ type: "spring", stiffness: 400, damping: 32 }}
          />
        )}
        <Icon size={17} className="relative z-10 shrink-0" />
        <span className="relative z-10 font-medium">{label}</span>
      </Link>
    );
  };

  const navBody = (
    <>
      <div className="flex items-center gap-2 px-2 py-3">
        <Logo size={32} />
        <span className="text-lg font-semibold tracking-tight">AegisAI</span>
      </div>
      <nav className="mt-3 flex-1 space-y-4 overflow-y-auto pr-1">
        {NAV_GROUPS.map((group) => (
          <div key={group.label}>
            <div className="px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-ink-soft/70">
              {group.label}
            </div>
            <div className="space-y-1">{group.items.map(renderLink)}</div>
          </div>
        ))}
        {isAdmin && (
          <div>
            <div className="px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-ink-soft/70">Admin</div>
            <div className="space-y-1">{ADMIN_NAV.map(renderLink)}</div>
          </div>
        )}
      </nav>
      <div className="mt-2 rounded-xl bg-surface-2 p-3 text-xs">
        <p className="truncate font-medium text-ink">{user?.email}</p>
        <p className="mt-0.5 capitalize text-ink-soft">{user?.role?.replace("_", " ")}</p>
        <button onClick={logout} className="mt-2 text-accent hover:underline">Sign out</button>
      </div>
    </>
  );

  return (
    <ProtectedRoute>
      <div className="flex min-h-screen">
        {/* Desktop sidebar */}
        <aside className="sticky top-0 hidden h-screen w-64 shrink-0 flex-col border-r border-line/10 bg-surface/60 p-4 backdrop-blur-xl md:flex">
          {navBody}
        </aside>

        {/* Mobile drawer */}
        <AnimatePresence>
          {mobileOpen && (
            <div className="fixed inset-0 z-50 flex md:hidden">
              <motion.div
                initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
                className="absolute inset-0 bg-black/50" onClick={() => setMobileOpen(false)}
              />
              <motion.aside
                initial={{ x: -280 }} animate={{ x: 0 }} exit={{ x: -280 }}
                transition={{ type: "spring", stiffness: 400, damping: 36 }}
                className="relative flex w-64 flex-col border-r border-line/10 bg-surface p-4"
              >
                <button onClick={() => setMobileOpen(false)} className="absolute right-3 top-3 text-ink-soft" aria-label="Close">
                  <X size={18} />
                </button>
                {navBody}
              </motion.aside>
            </div>
          )}
        </AnimatePresence>

        <div className="flex min-w-0 flex-1 flex-col">
          {/* Top bar */}
          <header className="sticky top-0 z-30 flex items-center justify-between gap-3 border-b border-line/10 bg-surface/60 px-4 py-2.5 backdrop-blur-xl sm:px-6">
            <button onClick={() => setMobileOpen(true)} aria-label="Open menu" className="text-ink md:hidden">
              <Menu size={20} />
            </button>
            <div className="hidden items-center gap-2 text-sm font-medium text-ink md:flex">
              {(() => {
                const current = [...ALL_NAV, ...ADMIN_NAV].find((n) => n.href === pathname);
                const Icon = current?.icon;
                return (
                  <>
                    {Icon && <Icon size={16} className="text-ink-soft" />}
                    {current?.label ?? "Dashboard"}
                  </>
                );
              })()}
            </div>
            <div className="ml-auto"><ThemeToggle /></div>
          </header>

          <AnimatePresence mode="wait">
            <motion.main
              key={pathname}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.25 }}
              className="flex-1 px-4 py-6 sm:px-6 sm:py-8"
            >
              {children}
            </motion.main>
          </AnimatePresence>
        </div>
      </div>
    </ProtectedRoute>
  );
}
