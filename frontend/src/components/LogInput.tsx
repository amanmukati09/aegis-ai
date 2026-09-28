"use client";

import { useCallback, useRef, useState } from "react";
import { FileText, Link2, UploadCloud } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { ingestApi } from "@/lib/platform-api";
import { ErrorText } from "@/components/ui";

type Tab = "paste" | "file" | "url";

/**
 * Unified log ingestion: paste text, drag-drop a file, or fetch from a URL.
 * Calls onLines() with the parsed, non-empty log lines.
 */
export function LogInput({ onLines }: { onLines: (lines: string[], meta: string) => void }) {
  const { token } = useAuth();
  const [tab, setTab] = useState<Tab>("paste");
  const [text, setText] = useState("");
  const [url, setUrl] = useState("");
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const toLines = (s: string) => s.split("\n").map((l) => l.trim()).filter(Boolean);

  const handleFile = useCallback((file: File) => {
    setError(null);
    if (file.size > 10 * 1024 * 1024) { setError("File too large (max 10MB)."); return; }
    const reader = new FileReader();
    reader.onload = () => {
      const lines = toLines(String(reader.result ?? ""));
      onLines(lines, `${file.name} (${lines.length} lines)`);
    };
    reader.readAsText(file);
  }, [onLines]);

  async function fetchUrl() {
    if (!token || !url.trim()) return;
    setBusy(true); setError(null);
    try {
      const res = await ingestApi.fromUrl(token, url.trim());
      onLines(res.lines, `${res.source} (${res.count} lines)`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Fetch failed");
    } finally { setBusy(false); }
  }

  const tabs: { id: Tab; label: string; icon: typeof FileText }[] = [
    { id: "paste", label: "Paste", icon: FileText },
    { id: "file", label: "Upload", icon: UploadCloud },
    { id: "url", label: "From URL", icon: Link2 },
  ];

  return (
    <div>
      <div className="mb-3 inline-flex rounded-xl border border-line/10 bg-surface-2 p-0.5">
        {tabs.map(({ id, label, icon: Icon }) => (
          <button key={id} onClick={() => setTab(id)}
            className={"flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors " +
              (tab === id ? "bg-accent-gradient text-white" : "text-ink-soft hover:text-ink")}>
            <Icon size={14} /> {label}
          </button>
        ))}
      </div>

      {tab === "paste" && (
        <>
          <textarea value={text} onChange={(e) => setText(e.target.value)} rows={9} placeholder="Paste raw logs…"
            className="w-full rounded-xl border border-line/10 bg-surface-2 p-4 font-mono text-xs text-ink outline-none focus:ring-2 focus:ring-accent/40" />
          <button onClick={() => onLines(toLines(text), `pasted (${toLines(text).length} lines)`)}
            className="btn-accent mt-3 h-10 px-5">Use pasted logs</button>
        </>
      )}

      {tab === "file" && (
        <div
          onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => { e.preventDefault(); setDragging(false); if (e.dataTransfer.files[0]) handleFile(e.dataTransfer.files[0]); }}
          onClick={() => fileRef.current?.click()}
          className={"flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed p-10 text-center transition-colors " +
            (dragging ? "border-accent bg-accent/5" : "border-line/15 hover:border-accent/40")}
        >
          <UploadCloud className="mb-2 text-ink-soft" size={28} />
          <p className="text-sm font-medium">Drag & drop a log file, or click to browse</p>
          <p className="mt-1 text-xs text-ink-soft">.log / .txt / .out · up to 10MB</p>
          <input ref={fileRef} type="file" accept=".log,.txt,.out,text/plain" className="hidden"
            onChange={(e) => e.target.files?.[0] && handleFile(e.target.files[0])} />
        </div>
      )}

      {tab === "url" && (
        <div className="flex gap-2">
          <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://example.com/app.log"
            className="input" />
          <button onClick={fetchUrl} disabled={busy} className="btn-accent h-11 whitespace-nowrap px-5">
            {busy ? "Fetching…" : "Fetch"}
          </button>
        </div>
      )}

      <ErrorText message={error} />
    </div>
  );
}
