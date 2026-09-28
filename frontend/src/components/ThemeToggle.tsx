"use client";

import { useTheme } from "next-themes";
import { Monitor, Moon, Sun } from "lucide-react";
import { useEffect, useState } from "react";

const OPTIONS = [
  { value: "light", icon: Sun, label: "Light" },
  { value: "system", icon: Monitor, label: "System" },
  { value: "dark", icon: Moon, label: "Dark" },
] as const;

export function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  if (!mounted) return <div className="h-8 w-24" />;

  return (
    <div className="inline-flex items-center gap-0.5 rounded-full border border-line/10 bg-surface p-0.5">
      {OPTIONS.map(({ value, icon: Icon, label }) => (
        <button
          key={value}
          onClick={() => setTheme(value)}
          aria-label={label}
          title={label}
          className={
            "flex h-7 w-7 items-center justify-center rounded-full transition-colors " +
            (theme === value ? "bg-accent-gradient text-white" : "text-ink-soft hover:text-ink")
          }
        >
          <Icon size={14} />
        </button>
      ))}
    </div>
  );
}
