"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { Button, Card, ErrorText, Field, Input, PasswordInput } from "@/components/ui";
import { Logo } from "@/components/Logo";

export default function RegisterPage() {
  const { register } = useAuth();
  const router = useRouter();
  const [form, setForm] = useState({ fullName: "", organizationName: "", email: "", password: "" });
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function update(key: keyof typeof form) {
    return (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: e.target.value });
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (form.password.length < 8) {
      setError("Password must be at least 8 characters");
      return;
    }
    setSubmitting(true);
    try {
      await register(form);
      router.replace("/dashboard");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Registration failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center px-6 py-10">
      <div className="pointer-events-none absolute inset-0 bg-surface-glow" />
      <div className="relative w-full max-w-sm">
        <div className="mb-8 text-center">
          <div className="mx-auto mb-3"><Logo size={48} /></div>
          <h1 className="text-3xl font-semibold tracking-tight">Create your workspace</h1>
          <p className="mt-1 text-sm text-ink-soft">Start with a new organization</p>
        </div>
        <Card>
          <form onSubmit={onSubmit} className="space-y-4">
            <Field label="Full name">
              <Input value={form.fullName} onChange={update("fullName")} required />
            </Field>
            <Field label="Organization name">
              <Input value={form.organizationName} onChange={update("organizationName")} required />
            </Field>
            <Field label="Email">
              <Input type="email" autoComplete="email" value={form.email} onChange={update("email")} required />
            </Field>
            <Field label="Password">
              <PasswordInput
                autoComplete="new-password"
                value={form.password}
                onChange={update("password")}
                required
              />
            </Field>
            <ErrorText message={error} />
            <Button type="submit" disabled={submitting}>
              {submitting ? "Creating…" : "Create account"}
            </Button>
          </form>
        </Card>
        <p className="mt-6 text-center text-sm text-ink-soft">
          Already have an account?{" "}
          <Link href="/login" className="font-medium text-accent hover:underline">
            Sign in
          </Link>
        </p>
      </div>
    </main>
  );
}
