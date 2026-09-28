"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Search, Send, Pencil, Trash2, RefreshCw, X, Plus, AlertTriangle } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import {
  deleteSession,
  getMessages,
  getModels,
  listSessions,
  messageSentiment,
  renameSession,
  searchChats,
  streamMessage,
  type ChatMessage,
  type ChatSearchHit,
  type ChatSession,
  type ModelInfo,
  type Sentiment,
} from "@/lib/chat-api";
import { ErrorText } from "@/components/ui";
import { Markdown } from "@/components/Markdown";

export default function CopilotPage() {
  const { token } = useAuth();
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [model, setModel] = useState<string>("");
  const [sending, setSending] = useState(false);
  const [streamingText, setStreamingText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameText, setRenameText] = useState("");
  const [sentiment, setSentiment] = useState<Sentiment | null>(null);

  // Search
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<ChatSearchHit[]>([]);

  const threadEnd = useRef<HTMLDivElement>(null);
  const abortRef = useRef<null | (() => void)>(null);

  const refreshSessions = useCallback(async () => {
    if (!token) return;
    setSessions(await listSessions(token));
  }, [token]);

  useEffect(() => {
    refreshSessions();
  }, [refreshSessions]);

  useEffect(() => {
    if (!token) return;
    getModels(token)
      .then((m) => {
        setModels(m.models);
        setModel(m.default_model);
      })
      .catch(() => setModels([]));
  }, [token]);

  useEffect(() => {
    if (!token || !activeId) {
      setMessages([]);
      return;
    }
    getMessages(token, activeId).then(setMessages).catch(() => setMessages([]));
  }, [token, activeId]);

  useEffect(() => {
    threadEnd.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streamingText, sending]);

  // Debounced chat search.
  useEffect(() => {
    if (!token || !searchOpen) return;
    const q = searchQuery.trim();
    if (q.length < 2) {
      setSearchResults([]);
      return;
    }
    const t = setTimeout(() => {
      searchChats(token, q).then(setSearchResults).catch(() => setSearchResults([]));
    }, 250);
    return () => clearTimeout(t);
  }, [token, searchOpen, searchQuery]);

  const doSend = useCallback(
    (text: string, sessionId: string | null) => {
      if (!token || !text.trim() || sending) return;
      setError(null);
      setStreamingText("");
      setMessages((m) => [
        ...m,
        { id: "temp-" + Date.now(), role: "user", content: text, createdAt: new Date().toISOString() },
      ]);
      setSending(true);

      // Best-effort operator sentiment on the outgoing message.
      messageSentiment(token, text).then(setSentiment).catch(() => setSentiment(null));

      let assembled = "";
      let resolvedSession = sessionId;

      abortRef.current = streamMessage(
        token,
        { sessionId: sessionId ?? undefined, message: text, model: model || undefined },
        {
          onSession: (id) => {
            resolvedSession = id;
            if (!sessionId) setActiveId(id);
          },
          onToken: (chunk) => {
            assembled += chunk;
            setStreamingText(assembled);
          },
          onDone: async () => {
            setSending(false);
            setStreamingText("");
            abortRef.current = null;
            if (resolvedSession) {
              try {
                setMessages(await getMessages(token, resolvedSession));
              } catch {
                /* keep optimistic messages */
              }
            }
            if (!sessionId) await refreshSessions();
          },
          onError: (msg) => {
            setSending(false);
            setStreamingText("");
            abortRef.current = null;
            setError(msg);
          },
        }
      );
    },
    [token, sending, model, refreshSessions]
  );

  function onSend() {
    const text = input.trim();
    if (!text) return;
    setInput("");
    doSend(text, activeId);
  }

  function onStop() {
    abortRef.current?.();
    abortRef.current = null;
    setSending(false);
    setStreamingText("");
  }

  function onEditLast() {
    const lastUser = [...messages].reverse().find((m) => m.role === "user");
    if (lastUser) setInput(lastUser.content);
  }

  function onRegenerate() {
    const lastUser = [...messages].reverse().find((m) => m.role === "user");
    if (lastUser) doSend(lastUser.content, activeId);
  }

  async function onDelete(id: string) {
    if (!token) return;
    if (!confirm("Delete this conversation?")) return;
    await deleteSession(token, id);
    if (activeId === id) {
      setActiveId(null);
      setMessages([]);
    }
    refreshSessions();
  }

  async function onRename(id: string) {
    if (!token || !renameText.trim()) {
      setRenamingId(null);
      return;
    }
    await renameSession(token, id, renameText.trim());
    setRenamingId(null);
    refreshSessions();
  }

  const hasMessages = messages.length > 0;

  return (
    <div className="flex h-[calc(100vh-5rem)] gap-4">
      {/* Sessions sidebar */}
      <aside className="glass hidden w-64 shrink-0 flex-col rounded-2xl p-3 md:flex">
        <button
          onClick={() => { setActiveId(null); setMessages([]); setSentiment(null); }}
          className="btn-accent mb-2 flex h-10 items-center justify-center gap-2 text-sm font-medium"
        >
          <Plus className="h-4 w-4" /> New chat
        </button>
        <button
          onClick={() => setSearchOpen(true)}
          className="btn-ghost mb-3 flex h-9 items-center justify-center gap-2 text-xs"
        >
          <Search className="h-3.5 w-3.5" /> Search conversations
        </button>
        <div className="flex-1 space-y-1 overflow-y-auto">
          {sessions.map((s) => (
            <div
              key={s.id}
              className={
                "group flex items-center gap-1 rounded-xl px-2 py-2 text-sm transition-colors " +
                (activeId === s.id ? "bg-surface-2 font-medium" : "hover:bg-surface-2")
              }
            >
              {renamingId === s.id ? (
                <input
                  autoFocus
                  value={renameText}
                  onChange={(e) => setRenameText(e.target.value)}
                  onBlur={() => onRename(s.id)}
                  onKeyDown={(e) => e.key === "Enter" && onRename(s.id)}
                  className="input h-7 flex-1 px-2 text-xs"
                />
              ) : (
                <button onClick={() => setActiveId(s.id)} className="flex-1 truncate text-left">
                  {s.title}
                </button>
              )}
              <button
                onClick={() => { setRenamingId(s.id); setRenameText(s.title); }}
                className="hidden text-ink-soft hover:text-accent group-hover:block"
                aria-label="Rename" title="Rename"
              >
                <Pencil className="h-3.5 w-3.5" />
              </button>
              <button
                onClick={() => onDelete(s.id)}
                className="hidden text-ink-soft hover:text-red-500 group-hover:block"
                aria-label="Delete" title="Delete"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}
          {sessions.length === 0 && <p className="px-3 py-2 text-xs text-ink-soft">No conversations yet.</p>}
        </div>
      </aside>

      {/* Thread */}
      <div className="glass flex flex-1 flex-col rounded-2xl">
        <div className="flex items-center justify-between border-b border-line/10 px-5 py-3">
          <div className="flex items-center gap-3">
            <h1 className="text-sm font-medium">AI Copilot</h1>
            {sentiment && sentiment.label !== "neutral" && (
              <SentimentBadge s={sentiment} />
            )}
          </div>
          <select
            value={model}
            onChange={(e) => setModel(e.target.value)}
            className="input h-8 w-auto px-2 text-xs"
          >
            {models.length === 0 && <option value="">default</option>}
            {models.map((m) => (
              <option key={m.id} value={m.id}>{m.id}</option>
            ))}
          </select>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto p-5">
          {!hasMessages && !sending && (
            <p className="mt-10 text-center text-sm text-ink-soft">
              Ask about an incident, a log snippet, or an SRE question.
            </p>
          )}
          {messages.map((m) => (
            <motion.div
              key={m.id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              className={"flex " + (m.role === "user" ? "justify-end" : "justify-start")}
            >
              <div
                className={
                  "max-w-[85%] rounded-2xl px-4 py-2.5 " +
                  (m.role === "user"
                    ? "whitespace-pre-wrap bg-accent text-sm text-white"
                    : "bg-surface-2")
                }
              >
                {m.role === "user" ? m.content : <Markdown content={m.content} />}
              </div>
            </motion.div>
          ))}

          {/* Live streaming assistant bubble */}
          {sending && (
            <div className="flex justify-start">
              <div className="max-w-[85%] rounded-2xl bg-surface-2 px-4 py-2.5">
                {streamingText ? (
                  <Markdown content={streamingText} />
                ) : (
                  <span className="inline-flex items-center gap-1 text-sm text-ink-soft">
                    <span className="h-2 w-2 animate-pulse rounded-full bg-accent" />
                    Thinking…
                  </span>
                )}
              </div>
            </div>
          )}
          <div ref={threadEnd} />
        </div>

        {/* Edit / regenerate / stop controls */}
        <div className="flex gap-3 border-t border-line/10 px-5 py-2 text-xs">
          {sending ? (
            <button onClick={onStop} className="text-ink-soft hover:text-red-500">Stop generating</button>
          ) : hasMessages ? (
            <>
              <button onClick={onEditLast} className="flex items-center gap-1 text-ink-soft hover:text-accent">
                <Pencil className="h-3 w-3" /> Edit last prompt
              </button>
              <button onClick={onRegenerate} className="flex items-center gap-1 text-ink-soft hover:text-accent">
                <RefreshCw className="h-3 w-3" /> Regenerate
              </button>
            </>
          ) : (
            <span className="text-ink-soft/60">Responses stream live.</span>
          )}
        </div>

        <div className="border-t border-line/10 p-4">
          <ErrorText message={error} />
          <div className="flex gap-2">
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && !e.shiftKey && (e.preventDefault(), onSend())}
              placeholder="Message AegisAI…"
              className="input h-11 flex-1 px-4 text-sm"
            />
            <button
              onClick={onSend}
              disabled={sending || !input.trim()}
              className="btn-accent flex h-11 items-center gap-2 px-5 text-sm font-medium disabled:opacity-50"
            >
              <Send className="h-4 w-4" /> Send
            </button>
          </div>
        </div>
      </div>

      {/* Search overlay */}
      <AnimatePresence>
        {searchOpen && (
          <motion.div
            className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-24 backdrop-blur-sm"
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            onClick={() => setSearchOpen(false)}
          >
            <motion.div
              className="glass w-full max-w-xl rounded-2xl p-4"
              initial={{ y: 12, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 12, opacity: 0 }}
              onClick={(e) => e.stopPropagation()}
            >
              <div className="mb-3 flex items-center gap-2">
                <Search className="h-4 w-4 text-ink-soft" />
                <input
                  autoFocus
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="Search your conversations…"
                  className="input h-10 flex-1 px-3 text-sm"
                />
                <button onClick={() => setSearchOpen(false)} className="text-ink-soft hover:text-ink">
                  <X className="h-4 w-4" />
                </button>
              </div>
              <div className="max-h-80 space-y-1 overflow-y-auto">
                {searchQuery.trim().length >= 2 && searchResults.length === 0 && (
                  <p className="px-2 py-3 text-center text-xs text-ink-soft">No matches.</p>
                )}
                {searchResults.map((hit, i) => (
                  <button
                    key={hit.sessionId + i}
                    onClick={() => { setActiveId(hit.sessionId); setSearchOpen(false); }}
                    className="w-full rounded-xl px-3 py-2 text-left transition-colors hover:bg-surface-2"
                  >
                    <div className="flex items-center gap-2 text-xs font-medium">
                      <span className="truncate">{hit.sessionTitle}</span>
                      <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[10px] uppercase text-ink-soft">
                        {hit.role}
                      </span>
                    </div>
                    <p className="mt-0.5 truncate text-xs text-ink-soft">{hit.snippet}</p>
                  </button>
                ))}
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function SentimentBadge({ s }: { s: Sentiment }) {
  const tone =
    s.urgency === "high"
      ? "bg-red-500/15 text-red-500 ring-red-500/30"
      : s.urgency === "elevated"
      ? "bg-amber-500/15 text-amber-500 ring-amber-500/30"
      : "bg-emerald-500/15 text-emerald-500 ring-emerald-500/30";
  return (
    <span
      className={"inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium uppercase ring-1 " + tone}
      title={`urgency: ${s.urgency}${s.signals?.length ? " · " + s.signals.join(", ") : ""}`}
    >
      {s.urgency === "high" && <AlertTriangle className="h-3 w-3" />}
      {s.label}
    </span>
  );
}
