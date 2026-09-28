import type { Config } from "tailwindcss";

// Apple-like design tokens: system font stack, muted palette, soft radius/shadows.
const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          "-apple-system",
          "BlinkMacSystemFont",
          "SF Pro Text",
          "Segoe UI",
          "Roboto",
          "Helvetica Neue",
          "Arial",
          "sans-serif",
        ],
      },
      colors: {
        ink: {
          DEFAULT: "#1d1d1f",
          soft: "#6e6e73",
        },
        surface: {
          DEFAULT: "#ffffff",
          muted: "#f5f5f7",
        },
        accent: {
          DEFAULT: "#0071e3",
          hover: "#0077ed",
        },
      },
      borderRadius: {
        xl: "1rem",
        "2xl": "1.25rem",
      },
      boxShadow: {
        card: "0 1px 2px rgba(0,0,0,0.04), 0 8px 24px rgba(0,0,0,0.06)",
      },
    },
  },
  plugins: [],
};

export default config;
