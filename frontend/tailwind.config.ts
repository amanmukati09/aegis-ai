import type { Config } from "tailwindcss";

// Premium dark-first design system with light support. Colors are driven by CSS
// variables (see globals.css) so dark/light swap cleanly. Gradient + glow accents.
const config: Config = {
  darkMode: "class",
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          "-apple-system",
          "BlinkMacSystemFont",
          "SF Pro Text",
          "Inter",
          "Segoe UI",
          "Roboto",
          "sans-serif",
        ],
        mono: ["SF Mono", "JetBrains Mono", "Menlo", "monospace"],
      },
      colors: {
        // Semantic tokens backed by CSS vars (RGB triplets).
        bg: "rgb(var(--bg) / <alpha-value>)",
        surface: {
          DEFAULT: "rgb(var(--surface) / <alpha-value>)",
          // Back-compat alias: `bg-surface-muted` maps to the secondary surface so
          // existing pages theme correctly in dark/light without edits.
          muted: "rgb(var(--surface-2) / <alpha-value>)",
        },
        "surface-2": "rgb(var(--surface-2) / <alpha-value>)",
        line: "rgb(var(--border) / <alpha-value>)",
        ink: "rgb(var(--ink) / <alpha-value>)",
        "ink-soft": "rgb(var(--ink-soft) / <alpha-value>)",
        accent: "rgb(var(--accent) / <alpha-value>)",
        "accent-2": "rgb(var(--accent-2) / <alpha-value>)",
      },
      borderRadius: {
        xl: "0.875rem",
        "2xl": "1.125rem",
        "3xl": "1.5rem",
      },
      boxShadow: {
        card: "0 1px 2px rgb(0 0 0 / 0.04), 0 8px 30px rgb(0 0 0 / 0.06)",
        "card-dark": "0 1px 2px rgb(0 0 0 / 0.3), 0 8px 30px rgb(0 0 0 / 0.35)",
        glow: "0 0 0 1px rgb(var(--accent) / 0.2), 0 8px 30px rgb(var(--accent) / 0.18)",
      },
      backgroundImage: {
        "accent-gradient": "linear-gradient(135deg, rgb(var(--accent)) 0%, rgb(var(--accent-2)) 100%)",
        "surface-glow": "radial-gradient(1200px 600px at 100% -10%, rgb(var(--accent) / 0.10), transparent)",
      },
      keyframes: {
        "fade-up": {
          "0%": { opacity: "0", transform: "translateY(8px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        shimmer: {
          "100%": { transform: "translateX(100%)" },
        },
      },
      animation: {
        "fade-up": "fade-up 0.4s ease-out both",
      },
    },
  },
  plugins: [],
};

export default config;
