"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect } from "react";
import { useAuth } from "@/lib/auth-context";

// OAuth2 success handler on the gateway redirects here with ?token=<jwt>.
function CallbackInner() {
  const params = useSearchParams();
  const router = useRouter();
  const { setToken } = useAuth();

  useEffect(() => {
    const token = params.get("token");
    if (!token) {
      router.replace("/login");
      return;
    }
    setToken(token)
      .then(() => router.replace("/dashboard"))
      .catch(() => router.replace("/login"));
  }, [params, router, setToken]);

  return (
    <div className="flex min-h-screen items-center justify-center text-ink-soft">
      Signing you in…
    </div>
  );
}

export default function AuthCallbackPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-ink-soft">Loading…</div>}>
      <CallbackInner />
    </Suspense>
  );
}
