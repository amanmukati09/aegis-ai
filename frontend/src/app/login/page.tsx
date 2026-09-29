"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { Button, Card, ErrorText, Field, Input, PasswordInput } from "@/components/ui";
import { Logo } from "@/components/Logo";

export default function LoginPage() {
  const { login, user, loading } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!loading && user) router.replace("/dashboard");
  }, [user, loading, router]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      router.replace("/dashboard");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center px-6">
      <div className="pointer-events-none absolute inset-0 bg-surface-glow" />
      <div className="relative w-full max-w-sm">
        <div className="mb-8 text-center">
          <div className="mx-auto mb-3"><Logo size={48} /></div>
          <h1 className="text-3xl font-semibold tracking-tight">AegisAI</h1>
          <p className="mt-1 text-sm text-ink-soft">Sign in to your account</p>
        </div>
        <Card>
          <form onSubmit={onSubmit} className="space-y-4">
            <Field label="Email">
              <Input
                type="email"
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            </Field>
            <Field label="Password">
              <PasswordInput
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </Field>
            <ErrorText message={error} />
            <Button type="submit" disabled={submitting}>
              {submitting ? "Signing in…" : "Sign in"}
            </Button>
          </form>
          <OAuthButtons />
        </Card>
        <p className="mt-6 text-center text-sm text-ink-soft">
          New here?{" "}
          <Link href="/register" className="font-medium text-accent hover:underline">
            Create an account
          </Link>
        </p>
      </div>
    </main>
  );
}

function OAuthButtons() {
  // OAuth2 login requires the gateway to be directly browser-reachable (the provider
  // redirect flow needs a real callback URL). Set NEXT_PUBLIC_GATEWAY_PUBLIC_URL to the
  // gateway's public address and configure provider keys in .env to enable these.
  const gatewayPublic = process.env.NEXT_PUBLIC_GATEWAY_PUBLIC_URL;
  if (!gatewayPublic) return null;
  const providers = [
    { id: "google", label: "Continue with Google" },
    { id: "github", label: "Continue with GitHub" },
    { id: "microsoft", label: "Continue with Microsoft" },
  ];
  return (
    <div className="mt-5 space-y-2 border-t border-line/10 pt-5">
      {providers.map((p) => (
        <a
          key={p.id}
          href={`${gatewayPublic}/oauth2/authorization/${p.id}`}
          className="btn-ghost flex h-11 w-full items-center justify-center"
        >
          {p.label}
        </a>
      ))}
    </div>
  );
}
