const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api";

export interface RequestOptions {
  method?: string;
  body?: unknown;
  token?: string | null;
  llmKey?: string;
  llmProvider?: string;
  llmModel?: string;
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
  });

  if (!res.ok) {
    let data = null;
    try {
      data = await res.json();
    } catch {
      // response wasn't JSON — keep the generic message
    }
    throw toApiError(res.status, data);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
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
    xhr.onerror = () => reject(new ApiError(0, "Network error — could not reach the server."));

    xhr.send(form);
  });
}
