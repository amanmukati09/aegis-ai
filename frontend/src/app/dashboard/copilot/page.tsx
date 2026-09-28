"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import {
  deleteSession,
  getMessages,
  getModels,
  listSessions,
  renameSession,
  sendMessage,
  type ChatMessage,
  type ChatSession,
  type ModelInfo,
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
  const [error, setError] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameText, setRenameText] = useState("");
  const threadEnd = useRef<HTMLDivElement>(null);

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
  }, [messages, sending]);

  const doSend = useCallback(
    async (text: string, sessionId: string | null) => {
      if (!token || !text.trim() || sending) return;
      setError(null);
      setMessages((m) => [
        ...m,
        { id: "temp-" + Date.now(), role: "user", content: text, createdAt: new Date().toISOString() },
      ]);
      setSending(true);
      try {
        const res = await sendMessage(token, {
          sessionId: sessionId ?? undefined,
          message: text,
          model: model || undefined,
        });
        if (!sessionId) {
          setActiveId(res.sessionId);
          await refreshSessions();
        }
        setMessages(await getMessages(token, res.sessionId));
      } catch (e) {
        setError(e instanceof Error ? e.message : "Failed to send");
      } finally {
        setSending(false);
      }
    },
    [token, sending, model, refreshSessions]
  );

  async function onSend() {
    const text = input.trim();
    if (!text) return;
    setInput("");
    await doSend(text, activeId);
  }

  // Edit the last user prompt: prefill the box with it and drop the trailing turn
  // locally so re-sending produces a fresh answer.
  function onEditLast() {
    const lastUser = [...messages].reverse().find((m) => m.role === "user");
    if (!lastUser) return;
    setInput(lastUser.content);
  }

  // Regenerate: resend the last user message to get a new answer.
  async function onRegenerate() {
    const lastUser = [...messages].reverse().find((m) => m.role === "user");
    if (lastUser) await doSend(lastUser.content, activeId);
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
    <div className="flex h-[calc(100vh-4rem)] gap-4">
      {/* Sessions sidebar */}
      <aside className="hidden w-64 shrink-0 flex-col rounded-2xl bg-surface p-3 shadow-card md:flex">
        <button
          onClick={() => { setActiveId(null); setMessages([]); }}
          className="mb-3 h-10 rounded-xl bg-accent text-sm font-medium text-white transition-colors hover:bg-accent-hover"
        >
          New chat
        </button>
        <div className="flex-1 space-y-1 overflow-y-auto">
          {sessions.map((s) => (
            <div
              key={s.id}
              className={
                "group flex items-center gap-1 rounded-xl px-2 py-2 text-sm transition-colors " +
                (activeId === s.id ? "bg-surface-muted font-medium" : "hover:bg-surface-muted")
              }
            >
              {renamingId === s.id ? (
                <input
                  autoFocus
                  value={renameText}
                  onChange={(e) => setRenameText(e.target.value)}
                  onBlur={() => onRename(s.id)}
                  onKeyDown={(e) => e.key === "Enter" && onRename(s.id)}
                  className="flex-1 rounded border border-black/10 bg-surface px-2 py-1 text-xs outline-none"
                />
              ) : (
                <button onClick={() => setActiveId(s.id)} className="flex-1 truncate text-left">
                  {s.title}
                </button>
              )}
              <button
                onClick={() => { setRenamingId(s.id); setRenameText(s.title); }}
                className="hidden text-ink-soft hover:text-accent group-hover:block"
                aria-label="Rename"
                title="Rename"
              >
                ✎
              </button>
              <button
                onClick={() => onDelete(s.id)}
                className="hidden text-ink-soft hover:text-red-600 group-hover:block"
                aria-label="Delete"
                title="Delete"
              >
                ✕
              </button>
            </div>
          ))}
          {sessions.length === 0 && <p className="px-3 py-2 text-xs text-ink-soft">No conversations yet.</p>}
        </div>
      </aside>

      {/* Thread */}
      <div className="flex flex-1 flex-col rounded-2xl bg-surface shadow-card">
        <div className="flex items-center justify-between border-b border-black/5 px-5 py-3">
          <h1 className="text-sm font-medium">AI Copilot</h1>
          <select
            value={model}
            onChange={(e) => setModel(e.target.value)}
            className="rounded-lg border border-black/10 bg-surface px-2 py-1 text-xs text-ink outline-none"
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
            <div key={m.id} className={"flex " + (m.role === "user" ? "justify-end" : "justify-start")}>
              <div
                className={
                  "max-w-[85%] rounded-2xl px-4 py-2.5 " +
                  (m.role === "user"
                    ? "whitespace-pre-wrap bg-accent text-sm text-white"
                    : "bg-surface-muted")
                }
              >
                {m.role === "user" ? m.content : <Markdown content={m.content} />}
              </div>
            </div>
          ))}
          {sending && (
            <div className="flex justify-start">
              <div className="rounded-2xl bg-surface-muted px-4 py-2.5 text-sm text-ink-soft">Thinking…</div>
            </div>
          )}
          <div ref={threadEnd} />
        </div>

        {/* Edit / regenerate controls */}
        {hasMessages && !sending && (
          <div className="flex gap-3 border-t border-black/5 px-5 py-2 text-xs">
            <button onClick={onEditLast} className="text-ink-soft hover:text-accent">Edit last prompt</button>
            <button onClick={onRegenerate} className="text-ink-soft hover:text-accent">Regenerate response</button>
          </div>
        )}

        <div className="border-t border-black/5 p-4">
          <ErrorText message={error} />
          <div className="flex gap-2">
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && !e.shiftKey && (e.preventDefault(), onSend())}
              placeholder="Message AegisAI…"
              className="h-11 flex-1 rounded-xl border border-black/10 bg-surface px-4 text-sm outline-none focus:ring-2 focus:ring-accent/40"
            />
            <button
              onClick={onSend}
              disabled={sending || !input.trim()}
              className="h-11 rounded-xl bg-accent px-5 text-sm font-medium text-white transition-colors hover:bg-accent-hover disabled:opacity-50"
            >
              Send
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
