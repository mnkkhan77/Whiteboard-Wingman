import { apiFetch } from "./client";
import type { AuthResponse } from "../types/api";

export function register(email: string, password: string, displayName: string) {
  return apiFetch<AuthResponse>("/auth/register", {
    method: "POST",
    body: { email, password, displayName },
  });
}

export function login(email: string, password: string) {
  return apiFetch<AuthResponse>("/auth/login", {
    method: "POST",
    body: { email, password },
  });
}

export function guestLogin(guestId: string) {
  return apiFetch<AuthResponse>("/auth/guest", {
    method: "POST",
    body: { guestId },
  });
}
