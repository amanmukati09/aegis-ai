import { apiDelete, apiGet, apiPost, apiPut } from "./api";

export type ChatSession = { id: string; title: string; createdAt: string };
export type ChatMessage = { id: string; role: string; content: string; createdAt: string };
export type SendResponse = { sessionId: string; reply: string; model: string };

export type ModelInfo = { id: string; provider: string; label: string | null };
export type ModelsResponse = {
  default_provider: string;
  default_model: string;
  available_providers: string[];
  models: ModelInfo[];
};

export function listSessions(token: string) {
  return apiGet<ChatSession[]>("/chat/sessions", token);
}

export function createSession(token: string, title?: string) {
  return apiPost<ChatSession>("/chat/sessions", { title }, token);
}

export function getMessages(token: string, sessionId: string) {
  return apiGet<ChatMessage[]>(`/chat/sessions/${sessionId}/messages`, token);
}

export function renameSession(token: string, sessionId: string, title: string) {
  return apiPut<ChatSession>(`/chat/sessions/${sessionId}/rename`, { title }, token);
}

export function deleteSession(token: string, sessionId: string) {
  return apiDelete<void>(`/chat/sessions/${sessionId}`, token);
}

export function sendMessage(
  token: string,
  input: { sessionId?: string; message: string; provider?: string; model?: string }
) {
  return apiPost<SendResponse>("/chat/message", input, token);
}

export function getModels(token: string) {
  return apiGet<ModelsResponse>("/chat/models", token);
}

export type ChatSearchHit = {
  sessionId: string;
  sessionTitle: string;
  role: string;
  snippet: string;
};

export function searchChats(token: string, q: string) {
  return apiGet<ChatSearchHit[]>(`/chat/search?q=${encodeURIComponent(q)}`, token);
}

export type Sentiment = {
  label: string;
  score: number;
  urgency: string;
  signals?: string[];
};

export function messageSentiment(token: string, text: string) {
  return apiPost<Sentiment>("/chat/sentiment", { text }, token);
}

/**
 * Stream an assistant reply token-by-token over SSE. Uses fetch + a stream reader rather
 * than EventSource because we need to POST a body and send an Authorization header.
 *
 * Callbacks:
 *  - onSession(id): fires once with the (possibly new) session id
 *  - onToken(chunk): fires for each content chunk
 *  - onDone(): fires when the stream terminates
 *  - onError(msg): fires on transport/parse error
 * Returns an abort function.
 */
export function streamMessage(
  token: string,
  input: { sessionId?: string; message: string; provider?: string; model?: string },
  handlers: {
    onSession?: (id: string) => void;
    onToken: (chunk: string) => void;
    onDone?: () => void;
    onError?: (msg: string) => void;
  }
): () => void {
  const controller = new AbortController();

  (async () => {
    try {
      const res = await fetch("/api/gateway/chat/stream", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
          Accept: "text/event-stream",
        },
        body: JSON.stringify(input),
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        handlers.onError?.(`HTTP ${res.status}`);
        return;
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      // SSE frames are separated by a blank line; each frame may carry `event:` and `data:`.
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        let sep: number;
        while ((sep = buffer.indexOf("\n\n")) !== -1) {
          const frame = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);

          let event: string | null = null;
          const dataLines: string[] = [];
          for (const line of frame.split("\n")) {
            if (line.startsWith("event:")) event = line.slice(6).trim();
            else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
          }
          const data = dataLines.join("\n");

          if (event === "session") {
            handlers.onSession?.(data);
          } else if (event === "error") {
            handlers.onError?.(data || "stream error");
          } else if (data === "[DONE]") {
            handlers.onDone?.();
            return;
          } else if (data.length > 0) {
            handlers.onToken(data);
          }
        }
      }
      handlers.onDone?.();
    } catch (err) {
      if ((err as Error)?.name !== "AbortError") {
        handlers.onError?.((err as Error)?.message ?? "stream failed");
      }
    }
  })();

  return () => controller.abort();
}
