import { createContext, useCallback, useContext, useState, type ReactNode } from "react";
import * as authApi from "../api/auth";
import type { AuthResponse } from "../types/api";

interface AuthState {
  token: string | null;
  email: string | null;
  displayName: string | null;
  role: string | null;
}

interface AuthContextValue extends AuthState {
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

const STORAGE_KEY = "mockinterview_auth";
const EMPTY_STATE: AuthState = { token: null, email: null, displayName: null, role: null };

function loadInitial(): AuthState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return JSON.parse(raw) as AuthState;
  } catch {
    // corrupted/blocked storage — fall back to logged-out state
  }
  return EMPTY_STATE;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(loadInitial);

  const persist = (auth: AuthResponse | null) => {
    if (auth) {
      const next: AuthState = { token: auth.token, email: auth.email, displayName: auth.displayName, role: auth.role };
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      } catch {
        // storage unavailable — session just won't survive a refresh
      }
      setState(next);
    } else {
      try {
        localStorage.removeItem(STORAGE_KEY);
      } catch {
        // ignore
      }
      setState(EMPTY_STATE);
    }
  };

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login(email, password);
    persist(res);
  }, []);

  const register = useCallback(async (email: string, password: string, displayName: string) => {
    const res = await authApi.register(email, password, displayName);
    persist(res);
  }, []);

  const logout = useCallback(() => persist(null), []);

  return (
    <AuthContext.Provider value={{ ...state, isAuthenticated: !!state.token, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
