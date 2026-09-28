"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useAuth } from "@/lib/auth-context";

export function ProtectedRoute({
  children,
  adminOnly = false,
}: {
  children: React.ReactNode;
  adminOnly?: boolean;
}) {
  const { user, loading, isAdmin } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (loading) return;
    if (!user) {
      router.replace("/login");
    } else if (adminOnly && !isAdmin) {
      router.replace("/dashboard");
    }
  }, [user, loading, isAdmin, adminOnly, router]);

  if (loading || !user || (adminOnly && !isAdmin)) {
    return (
      <div className="flex min-h-screen items-center justify-center text-ink-soft">
        Loading…
      </div>
    );
  }
  return <>{children}</>;
}
