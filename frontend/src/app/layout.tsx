import type { Metadata } from "next";
import "./globals.css";
import { AuthProvider } from "@/lib/auth-context";
import { ThemeProvider } from "@/components/ThemeProvider";

export const metadata: Metadata = {
  title: "AegisAI — Autonomous Incident Intelligence",
  description: "Detect, diagnose, and resolve incidents with AI.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body>
        <ThemeProvider>
          <AuthProvider>{children}</AuthProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
