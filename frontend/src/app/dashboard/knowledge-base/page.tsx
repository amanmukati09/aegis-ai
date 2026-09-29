"use client";

import { useCallback, useEffect, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { BookOpen, Search, Sparkles, Tag, X, ChevronRight } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { kbApi, type KbArticle, type KbSearchHit } from "@/lib/platform-api";
import { Card } from "@/components/ui";

export default function KnowledgeBasePage() {
  const { token } = useAuth();
  const [articles, setArticles] = useState<KbArticle[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<KbSearchHit[] | null>(null);
  const [active, setActive] = useState<KbArticle | null>(null);
  const [backfilling, setBackfilling] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try { setArticles(await kbApi.list(token)); } catch { setArticles([]); }
    finally { setLoading(false); }
  }, [token]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!token) return;
    const q = query.trim();
    if (q.length < 2) { setHits(null); return; }
    const t = setTimeout(() => {
      kbApi.search(token, q).then(setHits).catch(() => setHits([]));
    }, 250);
    return () => clearTimeout(t);
  }, [token, query]);

  async function onBackfill() {
    if (!token) return;
    setBackfilling(true);
    setMsg(null);
    try {
      const r = await kbApi.backfill(token);
      setMsg(`Generated ${r.created} article(s) from resolved incidents (${r.skipped} already had one, ${r.failed} failed).`);
      await load();
    } catch (e) {
      setMsg(e instanceof Error ? e.message : "Backfill failed");
    } finally {
      setBackfilling(false);
    }
  }

  const difficultyColor = (d: string) =>
    d === "Advanced" ? "bg-red-500/15 text-red-500"
    : d === "Beginner" ? "bg-emerald-500/15 text-emerald-500"
    : "bg-sky-500/15 text-sky-500";

  const list = query.trim().length >= 2 ? hits : null;

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
            <BookOpen className="h-6 w-6 text-accent" /> Knowledge Base
          </h1>
          <p className="mt-1 text-sm text-ink-soft">
            Articles distilled from resolved incidents — symptoms, root cause, solution, prevention.
          </p>
        </div>
        <button
          onClick={onBackfill}
          disabled={backfilling}
          className="btn-accent flex h-9 items-center gap-2 px-3 text-sm disabled:opacity-50"
        >
          <Sparkles className="h-4 w-4" /> {backfilling ? "Generating…" : "Generate from resolved"}
        </button>
      </div>

      {msg && <p className="mb-4 text-sm text-ink-soft">{msg}</p>}

      <div className="relative mb-4">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-soft" />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search articles…"
          className="input h-11 pl-9"
        />
      </div>

      {loading ? (
        <Card><p className="text-sm text-ink-soft">Loading…</p></Card>
      ) : (list ?? articles).length === 0 ? (
        <Card>
          <p className="text-sm text-ink-soft">
            {query.trim().length >= 2 ? "No matching articles." : "No articles yet. Resolve some incidents, then generate."}
          </p>
        </Card>
      ) : (
        <div className="space-y-2">
          {list
            ? list.map((h, i) => (
                <motion.button
                  key={h.id}
                  initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.03 }}
                  onClick={() => {
                    const full = articles.find((a) => a.id === h.id);
                    if (full) setActive(full);
                  }}
                  className="card flex w-full items-center justify-between p-4 text-left transition-colors hover:bg-surface-2"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium">{h.title}</p>
                    <p className="mt-0.5 truncate text-xs text-ink-soft">{h.snippet}</p>
                  </div>
                  <ChevronRight className="h-4 w-4 shrink-0 text-ink-soft" />
                </motion.button>
              ))
            : articles.map((a, i) => (
                <motion.button
                  key={a.id}
                  initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.03 }}
                  onClick={() => setActive(a)}
                  className="card flex w-full items-center justify-between p-4 text-left transition-colors hover:bg-surface-2"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <p className="truncate text-sm font-medium">{a.title}</p>
                      <span className={"shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium " + difficultyColor(a.difficulty)}>
                        {a.difficulty}
                      </span>
                    </div>
                    <p className="mt-0.5 truncate text-xs text-ink-soft">{a.symptoms}</p>
                    <div className="mt-1.5 flex flex-wrap gap-1">
                      {a.tags.slice(0, 4).map((t) => (
                        <span key={t} className="flex items-center gap-1 rounded bg-surface-2 px-1.5 py-0.5 text-[10px] text-ink-soft">
                          <Tag className="h-2.5 w-2.5" /> {t}
                        </span>
                      ))}
                    </div>
                  </div>
                  <ChevronRight className="ml-2 h-4 w-4 shrink-0 text-ink-soft" />
                </motion.button>
              ))}
        </div>
      )}

      <AnimatePresence>
        {active && (
          <motion.div
            className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-16 backdrop-blur-sm"
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            onClick={() => setActive(null)}
          >
            <motion.div
              className="glass max-h-[80vh] w-full max-w-2xl overflow-y-auto rounded-2xl p-6"
              initial={{ y: 12, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 12, opacity: 0 }}
              onClick={(e) => e.stopPropagation()}
            >
              <div className="mb-3 flex items-start justify-between gap-3">
                <div>
                  <h2 className="text-lg font-semibold">{active.title}</h2>
                  <div className="mt-1 flex items-center gap-2">
                    <span className="rounded bg-surface-2 px-2 py-0.5 text-xs text-ink-soft">{active.category}</span>
                    <span className={"rounded-full px-2 py-0.5 text-[10px] font-medium " + difficultyColor(active.difficulty)}>
                      {active.difficulty}
                    </span>
                  </div>
                </div>
                <button onClick={() => setActive(null)} className="text-ink-soft hover:text-ink">
                  <X className="h-5 w-5" />
                </button>
              </div>

              <div className="mt-4 flex flex-wrap gap-1">
                {active.tags.map((t) => (
                  <span key={t} className="rounded-full bg-surface-2 px-2 py-0.5 text-xs text-ink-soft">{t}</span>
                ))}
              </div>

              <ArticleSection title="Symptoms" color="text-red-500" text={active.symptoms} />
              <ArticleSection title="Root cause" color="text-amber-500" text={active.rootCause} />
              <ArticleSection title="Solution" color="text-emerald-500" text={active.solution} />
              <ArticleSection title="Prevention" color="text-sky-500" text={active.prevention} />
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function ArticleSection({ title, color, text }: { title: string; color: string; text: string }) {
  return (
    <div className="mt-4">
      <h3 className={"text-sm font-semibold " + color}>{title}</h3>
      <p className="mt-1 text-sm text-ink-soft">{text || "—"}</p>
    </div>
  );
}
