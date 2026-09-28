"use client";

import { animate, motion, useMotionValue, useTransform } from "framer-motion";
import { useEffect } from "react";

/** Animated metric card with count-up and gradient accent option. */
export function StatCard({
  label,
  value,
  accent = false,
  suffix = "",
  icon,
}: {
  label: string;
  value: number | string;
  accent?: boolean;
  suffix?: string;
  icon?: React.ReactNode;
}) {
  const numeric = typeof value === "number";
  const mv = useMotionValue(0);
  const rounded = useTransform(mv, (v) => Math.round(v).toLocaleString() + suffix);

  useEffect(() => {
    if (numeric) {
      const controls = animate(mv, value as number, { duration: 0.8, ease: "easeOut" });
      return controls.stop;
    }
  }, [value, numeric, mv]);

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className={
        "card relative overflow-hidden p-5 " +
        (accent ? "ring-1 ring-accent/30" : "")
      }
    >
      {accent && <div className="pointer-events-none absolute inset-0 bg-surface-glow" />}
      <div className="relative flex items-start justify-between">
        <p className="text-sm text-ink-soft">{label}</p>
        {icon && <span className="text-ink-soft">{icon}</span>}
      </div>
      <p className={"relative mt-2 text-3xl font-semibold tracking-tight " + (accent ? "text-accent" : "text-ink")}>
        {numeric ? <motion.span>{rounded}</motion.span> : value}
      </p>
    </motion.div>
  );
}
