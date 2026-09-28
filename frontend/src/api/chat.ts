import { apiFetch, apiStream, ApiError } from "./client";
import { STREAM_INTERRUPTED } from "../utils/chat";
import type {
  ChatDeltaEvent,
  ChatDoneEvent,
  ChatErrorEvent,
  ChatHistoryDto,
  ChatSourceDto,
  ChatSourcesEvent,
} from "../types/api";

/** Last 50 messages of this pack's chat, oldest first, plus the user's monthly quota. */
export function getChatHistory(token: string, packId: number, signal?: AbortSignal) {
  return apiFetch<ChatHistoryDto>(`/packs/${packId}/chat`, { token, signal });
}

export function clearChatHistory(token: string, packId: number) {
  return apiFetch<void>(`/packs/${packId}/chat`, { method: "DELETE", token });
}

export interface ChatStreamHandlers {
  /** The retrieved chunks, numbered from 1 — arrives before any delta (may be empty). */
  onSources?: (sources: ChatSourceDto[]) => void;
  /** Next piece of the answer; append in order. */
  onDelta?: (text: string) => void;
}

function parseData<T>(data: string): T {
  try {
    return JSON.parse(data) as T;
  } catch {
    throw new ApiError(200, "The server sent a malformed chat event.", STREAM_INTERRUPTED);
  }
}

/**
 * POST /packs/{id}/chat and read its SSE answer (`sources` → `delta`* → `done` | `error`).
 * Resolves with the `done` payload. Rejects with an ApiError whose `code` is the backend's —
 * a refusal before streaming (PACK_NOT_READY, CHAT_QUOTA_EXCEEDED, …), a mid-stream `error`
 * event (LLM_RATE_LIMITED / LLM_ERROR, status 200), or STREAM_INTERRUPTED when the stream ends
 * without either — or with the AbortError once `signal` aborts (no handler runs after that).
 */
export async function streamChat(
  token: string,
  packId: number,
  message: string,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal
): Promise<ChatDoneEvent> {
  // An object (not two `let`s) so TypeScript doesn't narrow the fields to null across the callback.
  const outcome: { done: ChatDoneEvent | null; failure: ApiError | null } = { done: null, failure: null };

  await apiStream(`/packs/${packId}/chat`, {
    token,
    body: { message },
    signal,
    onEvent: ({ event, data }) => {
      switch (event) {
        case "sources":
          handlers.onSources?.(parseData<ChatSourcesEvent>(data).sources ?? []);
          return;
        case "delta": {
          const { text } = parseData<ChatDeltaEvent>(data);
          if (text) handlers.onDelta?.(text);
          return;
        }
        case "done":
          outcome.done = parseData<ChatDoneEvent>(data);
          return false; // terminal — stop reading
        case "error": {
          const e = parseData<Partial<ChatErrorEvent>>(data);
          outcome.failure = new ApiError(200, e.message || "The answer failed to generate.", e.code || "LLM_ERROR");
          return false;
        }
        default:
          return; // unknown event types are ignored
      }
    },
  });

  if (outcome.failure) throw outcome.failure;
  if (!outcome.done) throw new ApiError(0, "The answer stream ended unexpectedly.", STREAM_INTERRUPTED);
  return outcome.done;
}
