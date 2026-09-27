import { apiFetch } from "./client";
import type { AdminStats, AdminUserDetail, AdminUserSummary, Page, Tier } from "../types/api";

export function listUsers(token: string, page: number, size = 20) {
  return apiFetch<Page<AdminUserSummary>>(`/admin/users?page=${page}&size=${size}`, { token });
}

export function getUserDetail(token: string, userId: number) {
  return apiFetch<AdminUserDetail>(`/admin/users/${userId}`, { token });
}

export function getStats(token: string) {
  return apiFetch<AdminStats>("/admin/stats", { token });
}

/** Changes a user's subscription tier; returns the refreshed admin user detail. */
export function updateUserTier(token: string, userId: number, tier: Tier) {
  return apiFetch<AdminUserDetail>(`/admin/users/${userId}/tier`, { method: "PUT", token, body: { tier } });
}
