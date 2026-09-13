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
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (opts.token) headers["Authorization"] = `Bearer ${opts.token}`;
  if (opts.llmKey) headers["X-LLM-Api-Key"] = opts.llmKey;
  if (opts.llmProvider) headers["X-LLM-Provider"] = opts.llmProvider;
  if (opts.llmModel) headers["X-LLM-Model"] = opts.llmModel;

  const res = await fetch(`${API_BASE}${path}`, {
    method: opts.method || "GET",
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });

  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const data = await res.json();
      message = data.message || message;
    } catch {
      // response wasn't JSON — keep the generic message
    }
    throw new ApiError(res.status, message);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}
