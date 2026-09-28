import { createSseParser, type SseEvent } from "../utils/sse";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api";

export interface RequestOptions {
  method?: string;
  body?: unknown;
  token?: string | null;
  llmKey?: string;
  llmProvider?: string;
  llmModel?: string;
  /** Aborting rejects the call with the fetch AbortError (a DOMException named "AbortError"). */
  signal?: AbortSignal;
}

export class ApiError extends Error {
  status: number;
  code?: string;
  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** Auth + BYO-LLM headers shared by apiFetch and apiUpload (no Content-Type — callers decide). */
function authHeaders(opts: Pick<RequestOptions, "token" | "llmKey" | "llmProvider" | "llmModel">): Record<string, string> {
  const headers: Record<string, string> = {};
  if (opts.token) headers["Authorization"] = `Bearer ${opts.token}`;
  if (opts.llmKey) headers["X-LLM-Api-Key"] = opts.llmKey;
  if (opts.llmProvider) headers["X-LLM-Provider"] = opts.llmProvider;
  if (opts.llmModel) headers["X-LLM-Model"] = opts.llmModel;
  return headers;
}

/** Builds an ApiError from the backend's `{ message, code? }` error JSON (or null if the body wasn't JSON). */
function toApiError(status: number, data: { message?: string; code?: string } | null): ApiError {
  return new ApiError(status, data?.message || `Request failed (${status})`, data?.code);
}

export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: opts.method || "GET",
    headers: { "Content-Type": "application/json", ...authHeaders(opts) },
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    signal: opts.signal,
  });

  if (!res.ok) throw await errorFromResponse(res);

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

async function errorFromResponse(res: Response): Promise<ApiError> {
  let data = null;
  try {
    data = await res.json();
  } catch {
    // response wasn't JSON — keep the generic message
  }
  return toApiError(res.status, data);
}

export function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

const NETWORK_ERROR_MESSAGE = "Network error — could not reach the server.";

export interface StreamOptions {
  token?: string | null;
  /** JSON request body. */
  body?: unknown;
  signal?: AbortSignal;
  /** Called for each server-sent event, in order. Return `false` to stop reading (the rest of the
   *  response is cancelled). A throw also stops reading and rejects the call with that error. */
  onEvent: (event: SseEvent) => boolean | void;
}

/**
 * POST whose response is `text/event-stream`. `EventSource` can't POST or send an Authorization
 * header, so this reads the body with fetch + ReadableStream and feeds it through the SSE parser
 * (TextDecoder in stream mode, so multi-byte characters split across chunks decode correctly).
 *
 * Resolves once the stream ends or `onEvent` returns false. Rejects with: the usual ApiError (with
 * `code`) for a non-2xx response before streaming starts; ApiError(0) for a network failure, also
 * mid-stream; the AbortError when `signal` aborts — and no `onEvent` call happens after that.
 */
export async function apiStream(path: string, opts: StreamOptions): Promise<void> {
  const { signal, onEvent } = opts;
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream", ...authHeaders(opts) },
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal,
    });
  } catch (err) {
    if (isAbortError(err)) throw err;
    throw new ApiError(0, NETWORK_ERROR_MESSAGE);
  }

  if (!res.ok) throw await errorFromResponse(res);
  if (!res.body) throw new ApiError(0, "This browser can't read streamed responses.");

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let stopped = false;
  let finished = false;
  const parser = createSseParser((event) => {
    if (!stopped && onEvent(event) === false) stopped = true;
  });

  try {
    while (!stopped) {
      let chunk: Awaited<ReturnType<typeof reader.read>>;
      try {
        chunk = await reader.read();
      } catch (err) {
        if (signal?.aborted) throw signal.reason;
        if (isAbortError(err)) throw err;
        throw new ApiError(0, NETWORK_ERROR_MESSAGE);
      }
      // A read that had already resolved when abort() ran must not deliver more events.
      signal?.throwIfAborted();
      if (chunk.done) {
        finished = true;
        parser.push(decoder.decode());
        parser.end();
        break;
      }
      parser.push(decoder.decode(chunk.value, { stream: true }));
    }
  } finally {
    if (!finished) reader.cancel().catch(() => {});
  }
}

export interface UploadOptions {
  token?: string | null;
  /** Called with 0–100 as the request body is sent. */
  onProgress?: (percent: number) => void;
}

/** Multipart POST. Uses XMLHttpRequest rather than fetch because fetch can't report upload
 *  progress. The Content-Type header is left unset so the browser adds the multipart boundary. */
export function apiUpload<T>(path: string, form: FormData, opts: UploadOptions = {}): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${API_BASE}${path}`);
    for (const [name, value] of Object.entries(authHeaders(opts))) xhr.setRequestHeader(name, value);

    const { onProgress } = opts;
    if (onProgress) {
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable && e.total > 0) onProgress(Math.round((e.loaded / e.total) * 100));
      };
    }

    xhr.onload = () => {
      let data = null;
      try {
        data = xhr.responseText ? JSON.parse(xhr.responseText) : null;
      } catch {
        // response wasn't JSON — keep the generic message
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data as T);
      else reject(toApiError(xhr.status, data));
    };
    xhr.onerror = () => reject(new ApiError(0, NETWORK_ERROR_MESSAGE));

    xhr.send(form);
  });
}
