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
