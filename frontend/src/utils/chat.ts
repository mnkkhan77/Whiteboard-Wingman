// Pure helpers for "chat with a pack" — error-code → friendly text, quota maths/formatting and
// source labels. Framework-free so they're trivially testable.
import type { ChatQuotaDto, ChatSourceDto } from "../types/api";

/** Matches the backend's 1–2000 chars (trimmed) validation. */
export const CHAT_MESSAGE_MAX_CHARS = 2000;

/** Client-side code for a stream that ended without a `done` or `error` event. */
export const STREAM_INTERRUPTED = "STREAM_INTERRUPTED";

export const CHAT_EXAMPLE_PROMPTS = [
  "Summarise the key ideas in this document.",
  "Explain the most important concept in simple terms.",
  "What are the main definitions I should memorise?",
];

type ErrorLike = { status: number; message: string; code?: string };

/** The monthly chat quota resets at 00:00 UTC on the 1st (calendar month, UTC). */
export function nextQuotaReset(now: Date = new Date()): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
}

/** e.g. "October 1" — formatted in UTC so it names the day the reset actually happens on. */
export function formatQuotaReset(now: Date = new Date()): string {
  return nextQuotaReset(now).toLocaleDateString(undefined, { month: "long", day: "numeric", timeZone: "UTC" });
}

export function formatTokens(n: number): string {
  return Math.max(0, Math.round(n)).toLocaleString();
}

/** A negative limit is treated as unlimited (same convention as maxPacks). */
export function isUnlimitedQuota(quota: ChatQuotaDto): boolean {
  return quota.limit < 0;
}

/** Mirrors the backend's refusal rule: refused once used >= limit. */
export function isQuotaExhausted(quota: ChatQuotaDto | null): boolean {
  return !!quota && !isUnlimitedQuota(quota) && quota.used >= quota.limit;
}

/** 0–100, clamped (one answer may overshoot the limit slightly). */
export function quotaPercent(quota: ChatQuotaDto): number {
  if (isUnlimitedQuota(quota)) return 0;
  if (quota.limit === 0) return 100;
  return Math.min(100, Math.max(0, (quota.used / quota.limit) * 100));
}

export function quotaResetMessage(now: Date = new Date()): string {
  return `You've used this month's chat allowance on your plan. It resets on ${formatQuotaReset(now)}.`;
}

/** Friendly text for a failed chat request — pre-stream JSON errors, mid-stream `error` events
 *  and network failures (status 0). Falls back to the server's message, then a generic line. */
export function chatErrorMessage(err: ErrorLike, now: Date = new Date()): string {
  switch (err.code) {
    case "PACK_NOT_READY":
      return "This pack is still being processed — you can chat with it once it's ready.";
    case "CHAT_QUOTA_EXCEEDED":
      return quotaResetMessage(now);
    case "CHAT_UNAVAILABLE":
      return "Chat is temporarily unavailable. Please try again later.";
    case "LLM_RATE_LIMITED":
      return "The AI service is busy right now. Wait a few seconds and try again.";
    case "LLM_ERROR":
      return "Something went wrong while generating the answer. Please try again.";
    case STREAM_INTERRUPTED:
      return "The connection dropped before the answer finished. Please try again.";
  }
  if (err.status === 0) return "Network error — check your connection and try again.";
  if (err.status === 404) return "This study pack doesn't exist or was deleted.";
  if (err.status === 400) return err.message || `Messages must be 1–${CHAT_MESSAGE_MAX_CHARS} characters.`;
  return err.message || "Something went wrong. Please try again.";
}

/** Whether offering "Retry" makes sense — not for refusals that will just happen again. */
export function isRetryableChatError(err: ErrorLike): boolean {
  if (err.code === "CHAT_QUOTA_EXCEEDED" || err.code === "PACK_NOT_READY") return false;
  return err.status !== 400 && err.status !== 401 && err.status !== 403 && err.status !== 404;
}

/** "Pages 3–4", "Page 3", or null when the format has no pages. */
export function formatSourcePages(source: Pick<ChatSourceDto, "page" | "pageEnd">): string | null {
  if (source.page == null) return null;
  if (source.pageEnd != null && source.pageEnd !== source.page) return `Pages ${source.page}–${source.pageEnd}`;
  return `Page ${source.page}`;
}

/** Short label for a citation chip's accessible name, e.g. "Source 2, page 3–4". */
export function describeSource(source: ChatSourceDto): string {
  const pages = formatSourcePages(source);
  return pages ? `Source ${source.n}, ${pages.toLowerCase()}` : `Source ${source.n}`;
}
