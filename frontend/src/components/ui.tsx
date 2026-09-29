import { type ButtonHTMLAttributes, type InputHTMLAttributes, forwardRef, useState } from "react";

export function Card({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return <div className={`card p-6 ${className}`}>{children}</div>;
}

export const Button = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement>>(
  function Button({ className = "", children, ...props }, ref) {
    return (
      <button ref={ref} className={`btn-accent h-11 w-full ${className}`} {...props}>
        {children}
      </button>
    );
  }
);

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", ...props }, ref) {
    return <input ref={ref} className={`input ${className}`} {...props} />;
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
  return (
    <p className="rounded-lg border border-red-500/20 bg-red-500/10 px-3 py-2 text-sm text-red-500">
      {message}
    </p>
  );
}

export function SeverityBadge({ severity }: { severity?: string | null }) {
  const s = (severity ?? "unknown").toLowerCase();
  const styles: Record<string, string> = {
    critical: "bg-red-500/15 text-red-500 ring-red-500/25",
    high: "bg-orange-500/15 text-orange-500 ring-orange-500/25",
    medium: "bg-amber-500/15 text-amber-600 dark:text-amber-400 ring-amber-500/25",
    low: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 ring-emerald-500/25",
    unknown: "bg-ink/10 text-ink-soft ring-ink/15",
  };
  return (
    <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium capitalize ring-1 ${styles[s] ?? styles.unknown}`}>
      {severity ?? "unknown"}
    </span>
  );
}

export function StatusBadge({ status }: { status?: string | null }) {
  const s = (status ?? "open").toLowerCase();
  const styles: Record<string, string> = {
    open: "bg-blue-500/15 text-blue-500 ring-blue-500/25",
    resolved: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 ring-emerald-500/25",
    closed: "bg-ink/10 text-ink-soft ring-ink/15",
  };
  return (
    <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium capitalize ring-1 ${styles[s] ?? "bg-ink/10 text-ink-soft ring-ink/15"}`}>
      {status ?? "open"}
    </span>
  );
}

/** Password field with a show/hide toggle. */
export const PasswordInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function PasswordInput({ className = "", ...props }, ref) {
    const [visible, setVisible] = useState(false);
    return (
      <div className="relative">
        <input
          ref={ref}
          type={visible ? "text" : "password"}
          className={`input pr-16 ${className}`}
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

/** Skeleton loading block. */
export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`skeleton ${className}`} />;
}

/**
 * Page header: title + subtitle + optional right-aligned actions. Used at the top of
 * every dashboard page so headings are visually consistent across the app.
 */
export function PageHeader({
  title,
  subtitle,
  icon,
  actions,
}: {
  title: string;
  subtitle?: string;
  icon?: React.ReactNode;
  actions?: React.ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
          {icon}
          {title}
        </h1>
        {subtitle && <p className="mt-1 text-sm text-ink-soft">{subtitle}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  );
}

/** Empty-state placeholder: icon + message, used instead of a bare "No X yet" line. */
export function EmptyState({
  icon,
  title,
  hint,
}: {
  icon?: React.ReactNode;
  title: string;
  hint?: string;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-14 text-center">
      {icon && <div className="mb-1 text-ink-soft/50">{icon}</div>}
      <p className="text-sm font-medium text-ink-soft">{title}</p>
      {hint && <p className="max-w-xs text-xs text-ink-soft/70">{hint}</p>}
    </div>
  );
}

/** Token-consistent data table shell — header row + body, used across list pages. */
export function Table({
  columns,
  children,
}: {
  columns: string[];
  children: React.ReactNode;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-line/10 text-left text-xs uppercase tracking-wide text-ink-soft">
            {columns.map((c) => (
              <th key={c} className="px-5 py-3 font-medium">{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

export function TableRow({
  onClick,
  className = "",
  children,
}: {
  onClick?: () => void;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <tr
      onClick={onClick}
      className={
        "border-b border-line/10 transition-colors last:border-0 " +
        (onClick ? "cursor-pointer hover:bg-surface-2 " : "") + className
      }
    >
      {children}
    </tr>
  );
}
