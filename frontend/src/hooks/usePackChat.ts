import { useCallback, useEffect, useRef, useState } from "react";
import { getPack } from "../api/packs";
import { clearChatHistory, getChatHistory, streamChat } from "../api/chat";
import { ApiError } from "../api/client";
import { CHAT_MESSAGE_MAX_CHARS, chatErrorMessage, isRetryableChatError } from "../utils/chat";
import { markdownLiteToPlainText, parseMarkdownLite } from "../utils/markdownLite";
import type { ChatMessageDto, ChatQuotaDto, ChatRole, ChatSourceDto, PackDto } from "../types/api";

/** A message as the chat page shows it — a persisted ChatMessageDto or a local one in flight. */
export interface ChatItem {
  /** Stable React key: `m-{id}` for loaded history, `local-{n}-…` for messages sent here. */
  key: string;
  /** Server id; absent on the user's local message and on an answer until its `done` arrives. */
  id?: number;
  role: ChatRole;
  content: string;
  sources: ChatSourceDto[];
  citedSources: number[];
  /** Answer still arriving (typing indicator until the first delta). */
  streaming?: boolean;
  /** The user pressed Stop — partial text is shown but the server didn't save it. */
  stopped?: boolean;
}

/** The last send failed: its user message stays visible, the partial answer is dropped. */
export interface ChatFailure {
  userKey: string;
  text: string;
  message: string;
  retryable: boolean;
}

export interface ChatLoadError {
  message: string;
  notFound: boolean;
}

interface ActiveRequest {
  controller: AbortController;
  /** Set when the page goes away — the request's late settle must not touch state at all. */
  discard: boolean;
}

function fromDto(m: ChatMessageDto): ChatItem {
  return {
    key: `m-${m.id}`,
    id: m.id,
    role: m.role,
    content: m.content,
    sources: m.sources ?? [],
    citedSources: m.citedSources ?? [],
  };
}

function toApiError(err: unknown): ApiError {
  return err instanceof ApiError ? err : new ApiError(0, "Network error — could not reach the server.");
}

/**
 * State + streaming for one pack's chat: loads the pack and its history once, sends a message and
 * grows the answer as `delta`s arrive, Stop / retry / clear, and the monthly quota.
 *
 * Intended to be mounted once per pack (the page keys its component by packId), so state never
 * carries over between packs. Leaving the page aborts the load and any in-flight answer, and a
 * request that settles after that is ignored. Pass `null` (logged out or guest) to skip requests.
 */
export function usePackChat(token: string | null, packId: number) {
  const [pack, setPack] = useState<PackDto | null>(null);
  const [items, setItems] = useState<ChatItem[] | null>(null);
  const [quota, setQuota] = useState<ChatQuotaDto | null>(null);
  const [loadError, setLoadError] = useState<ChatLoadError | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [streaming, setStreaming] = useState(false);
  const [failure, setFailure] = useState<ChatFailure | null>(null);
  // Polite screen-reader announcement ("Answering…", then the finished answer as plain text).
  const [announcement, setAnnouncement] = useState("");

  const activeRef = useRef<ActiveRequest | null>(null);
  const seqRef = useRef(0);
  const mountedRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // Pack + history in parallel, once per (token, pack, explicit reload). Aborted on cleanup, so a
  // StrictMode double-mount or a fast reload never lets an older response land after a newer one.
  useEffect(() => {
    if (!token) return;
    const controller = new AbortController();
    const { signal } = controller;
    Promise.all([
      getPack(token, packId, signal),
      // A not-yet-READY pack may refuse chat calls; the page shows the pack's status instead.
      getChatHistory(token, packId, signal).catch((err) => {
        if (err instanceof ApiError && err.code === "PACK_NOT_READY") return null;
        throw err;
      }),
    ])
      .then(([p, history]) => {
        if (signal.aborted) return;
        setPack(p);
        setItems(history ? history.messages.map(fromDto) : []);
        setQuota(history?.quota ?? null);
      })
      .catch((err) => {
        if (signal.aborted) return;
        const e = toApiError(err);
        const friendly = e.status === 404 || e.status === 0;
        setLoadError({
          notFound: e.status === 404,
          message: friendly ? chatErrorMessage(e) : e.message || "Could not load this chat.",
        });
      });
    return () => controller.abort();
  }, [token, packId, reloadKey]);

  // Leaving the page (or switching token/pack) cancels the answer being streamed.
  useEffect(() => {
    return () => {
      const active = activeRef.current;
      if (active) {
        active.discard = true;
        active.controller.abort();
        activeRef.current = null;
      }
    };
  }, [token, packId]);

  const send = useCallback(
    async (raw: string) => {
      const message = raw.trim().slice(0, CHAT_MESSAGE_MAX_CHARS);
      if (!token || !message || activeRef.current) return;

      const request: ActiveRequest = { controller: new AbortController(), discard: false };
      activeRef.current = request;
      const n = ++seqRef.current;
      const userKey = `local-${n}-user`;
      const answerKey = `local-${n}-answer`;
      const updateAnswer = (fn: (item: ChatItem) => ChatItem) =>
        setItems((prev) => prev?.map((i) => (i.key === answerKey ? fn(i) : i)) ?? prev);

      setFailure(null);
      setStreaming(true);
      setAnnouncement("Answering…");
      setItems((prev) => [
        ...(prev ?? []),
        { key: userKey, role: "USER", content: message, sources: [], citedSources: [] },
        { key: answerKey, role: "ASSISTANT", content: "", sources: [], citedSources: [], streaming: true },
      ]);

      // Kept alongside state so the final answer can be announced without reading state back.
      let answer = "";
      let sourceCount = 0;
      try {
        const done = await streamChat(
          token,
          packId,
          message,
          {
            onSources: (sources) => {
              sourceCount = sources.length;
              updateAnswer((i) => ({ ...i, sources }));
            },
            onDelta: (text) => {
              answer += text;
              updateAnswer((i) => ({ ...i, content: i.content + text }));
            },
          },
          request.controller.signal
        );
        if (request.discard) return;
        updateAnswer((i) => ({ ...i, id: done.messageId, citedSources: done.citedSources ?? [], streaming: false }));
        if (done.quota) setQuota(done.quota);
        setAnnouncement(markdownLiteToPlainText(parseMarkdownLite(answer, sourceCount)) || "Answer ready.");
      } catch (err) {
        if (request.discard) return;
        if (request.controller.signal.aborted) {
          // Stop: keep whatever arrived (marked as unsaved); drop an answer that never started.
          setItems(
            (prev) =>
              prev?.flatMap((i) => {
                if (i.key !== answerKey) return [i];
                return i.content ? [{ ...i, streaming: false, stopped: true }] : [];
              }) ?? prev
          );
          setAnnouncement("Stopped.");
          return;
        }
        const e = toApiError(err);
        setItems((prev) => prev?.filter((i) => i.key !== answerKey) ?? prev);
        setFailure({ userKey, text: message, message: chatErrorMessage(e), retryable: isRetryableChatError(e) });
        setAnnouncement("");
        if (e.code === "CHAT_QUOTA_EXCEEDED") {
          // The server says we're out — reflect it even if our last reading was stale.
          setQuota((q) => (q ? { ...q, used: Math.max(q.used, q.limit) } : q));
        }
      } finally {
        if (!request.discard && mountedRef.current) {
          if (activeRef.current === request) activeRef.current = null;
          setStreaming(false);
        }
      }
    },
    [token, packId]
  );

  const stop = useCallback(() => {
    activeRef.current?.controller.abort();
  }, []);

  /** Re-sends the failed message; its old bubble is replaced by the new attempt's. */
  const retry = useCallback(() => {
    if (!failure || activeRef.current) return;
    const { userKey, text } = failure;
    setItems((prev) => prev?.filter((i) => i.key !== userKey) ?? prev);
    void send(text);
  }, [failure, send]);

  /** Throws the ApiError on failure so the caller can show it next to its confirm button. */
  const clear = useCallback(async () => {
    if (!token || activeRef.current) return;
    await clearChatHistory(token, packId);
    if (!mountedRef.current) return;
    setItems([]);
    setFailure(null);
    setAnnouncement("Chat cleared.");
  }, [token, packId]);

  const reload = useCallback(() => {
    setLoadError(null);
    setReloadKey((k) => k + 1);
  }, []);

  return { pack, items, quota, loadError, reload, streaming, failure, announcement, send, stop, retry, clear };
}
