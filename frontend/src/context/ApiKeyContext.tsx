import { createContext, useCallback, useContext, useState, type ReactNode } from "react";
import type { LlmProvider } from "../types/api";

interface ApiKeyState {
  apiKey: string;
  provider: LlmProvider;
  model: string;
}

interface ApiKeyContextValue extends ApiKeyState {
  hasKey: boolean;
  setApiKey: (key: string) => void;
  setProvider: (provider: LlmProvider) => void;
  setModel: (model: string) => void;
  clear: () => void;
}

const ApiKeyContext = createContext<ApiKeyContextValue | undefined>(undefined);

// sessionStorage only, per PLAN.md §8: cleared on tab close, never sent anywhere but our own
// API, and never persisted server-side — this is the web adaptation of fitforge's BYO-key pattern.
const STORAGE_KEY = "mockinterview_llm_key";
const DEFAULT_STATE: ApiKeyState = { apiKey: "", provider: "GROQ", model: "" };

function loadInitial(): ApiKeyState {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (raw) return JSON.parse(raw) as ApiKeyState;
  } catch {
    // private browsing / blocked storage — fall back to empty
  }
  return DEFAULT_STATE;
}

export function ApiKeyProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ApiKeyState>(loadInitial);

  const persist = (next: ApiKeyState) => {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // ignore — key just won't survive a page reload this tab
    }
    setState(next);
  };

  const setApiKey = (key: string) => persist({ ...state, apiKey: key });
  const setProvider = (provider: LlmProvider) => persist({ ...state, provider });
  const setModel = (model: string) => persist({ ...state, model });
  const clear = useCallback(() => {
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      // ignore
    }
    setState(DEFAULT_STATE);
  }, []);

  return (
    <ApiKeyContext.Provider value={{ ...state, hasKey: !!state.apiKey, setApiKey, setProvider, setModel, clear }}>
      {children}
    </ApiKeyContext.Provider>
  );
}

export function useApiKey() {
  const ctx = useContext(ApiKeyContext);
  if (!ctx) throw new Error("useApiKey must be used within ApiKeyProvider");
  return ctx;
}
