/**
 * AegisAI brand mark — a shield with an inner pulse/waveform, evoking "guarded system
 * health." Pure inline SVG (no image asset to ship/cache-bust), single source of truth
 * so the mark is never duplicated/drifted between the sidebar, mobile drawer, and auth
 * pages.
 */
export function LogoMark({ className = "h-5 w-5" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path
        d="M12 2 4 5v6c0 5 3.5 8.5 8 11 4.5-2.5 8-6 8-11V5l-8-3Z"
        fill="currentColor"
        fillOpacity={0.16}
        stroke="currentColor"
        strokeWidth={1.6}
        strokeLinejoin="round"
      />
      <path
        d="M7 12.5h2.2l1.4-3 2 6 1.4-3H17"
        stroke="currentColor"
        strokeWidth={1.6}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** The mark inside its gradient tile, at a given size — the reusable brand unit. */
export function Logo({ size = 32, className = "" }: { size?: number; className?: string }) {
  return (
    <div
      style={{ width: size, height: size }}
      className={`flex shrink-0 items-center justify-center rounded-xl bg-accent-gradient text-white shadow-glow ${className}`}
    >
      <LogoMark className="h-[55%] w-[55%]" />
    </div>
  );
}

/** Logo + wordmark, for auth pages and anywhere the full brand lockup is wanted. */
export function LogoLockup({ size = 40 }: { size?: number }) {
  return (
    <div className="flex flex-col items-center gap-3">
      <Logo size={size} />
      <span className="text-2xl font-semibold tracking-tight">AegisAI</span>
    </div>
  );
}
