import {
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  forwardRef,
  useState,
} from "react";

export function Card({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return <div className={`rounded-2xl bg-surface p-6 shadow-card ${className}`}>{children}</div>;
}

export const Button = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement>>(
  function Button({ className = "", children, ...props }, ref) {
    return (
      <button
        ref={ref}
        className={
          "inline-flex h-11 w-full items-center justify-center rounded-xl bg-accent px-5 text-sm font-medium text-white " +
          "transition-colors hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-50 " +
          className
        }
        {...props}
      >
        {children}
      </button>
    );
  }
);

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", ...props }, ref) {
    return (
      <input
        ref={ref}
        className={
          "h-11 w-full rounded-xl border border-black/10 bg-surface px-4 text-sm text-ink " +
          "outline-none transition-shadow placeholder:text-ink-soft focus:ring-2 focus:ring-accent/40 " +
          className
        }
        {...props}
      />
    );
  }
);

export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block space-y-1.5">
      <span className="text-sm font-medium text-ink">{label}</span>
      {children}
    </label>
  );
}

export function ErrorText({ message }: { message?: string | null }) {
  if (!message) return null;
  return <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{message}</p>;
}

export function SeverityBadge({ severity }: { severity?: string | null }) {
  const s = (severity ?? "unknown").toLowerCase();
  const styles: Record<string, string> = {
    critical: "bg-red-100 text-red-700",
    high: "bg-orange-100 text-orange-700",
    medium: "bg-amber-100 text-amber-700",
    low: "bg-emerald-100 text-emerald-700",
    unknown: "bg-black/5 text-ink-soft",
  };
  return (
    <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium capitalize ${styles[s] ?? styles.unknown}`}>
      {severity ?? "unknown"}
    </span>
  );
}

export function StatusBadge({ status }: { status?: string | null }) {
  const s = (status ?? "open").toLowerCase();
  const styles: Record<string, string> = {
    open: "bg-blue-100 text-blue-700",
    resolved: "bg-emerald-100 text-emerald-700",
    closed: "bg-black/5 text-ink-soft",
  };
  return (
    <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium capitalize ${styles[s] ?? "bg-black/5 text-ink-soft"}`}>
      {status ?? "open"}
    </span>
  );
}

/**
 * Password field with a show/hide toggle. Reused by every credential form.
 * The toggle button is accessible (aria-label + aria-pressed) and doesn't submit.
 */
export const PasswordInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function PasswordInput({ className = "", ...props }, ref) {
    const [visible, setVisible] = useState(false);
    return (
      <div className="relative">
        <input
          ref={ref}
          type={visible ? "text" : "password"}
          className={
            "h-11 w-full rounded-xl border border-black/10 bg-surface px-4 pr-16 text-sm text-ink " +
            "outline-none transition-shadow placeholder:text-ink-soft focus:ring-2 focus:ring-accent/40 " +
            className
          }
          {...props}
        />
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          aria-label={visible ? "Hide password" : "Show password"}
          aria-pressed={visible}
          className="absolute inset-y-0 right-0 flex items-center px-3 text-xs font-medium text-ink-soft transition-colors hover:text-ink"
        >
          {visible ? "Hide" : "Show"}
        </button>
      </div>
    );
  }
);
