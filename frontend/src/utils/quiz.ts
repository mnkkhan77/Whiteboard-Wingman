// Pure helpers for "quiz from a pack" (Phase 4) — question-count bounds, bank status display and
// error-code → friendly text. Framework-free so they're trivially testable.
import { chatErrorMessage, formatQuotaReset } from "./chat";
import { clampInt } from "./numberInput";
import type { ChatQuotaDto, PackDto, PackLimitsDto, QuizStatus } from "../types/api";

/** Backend bounds for a pack session's `questionCount` (default 8). */
export const QUIZ_MIN_QUESTIONS = 2;
export const QUIZ_MAX_QUESTIONS = 20;
export const QUIZ_DEFAULT_QUESTIONS = 8;

type ErrorLike = { status: number; message: string; code?: string };

/** Most questions worth asking for: 20, or fewer when the bank is smaller (the backend caps by what
 *  the bank has anyway). Never below the minimum, so the range stays valid. */
export function quizQuestionLimit(pack: Pick<PackDto, "quizQuestionCount">): number {
  const bank = pack.quizQuestionCount;
  if (bank == null || bank <= 0) return QUIZ_MAX_QUESTIONS;
  return clampInt(bank, QUIZ_MIN_QUESTIONS, QUIZ_MAX_QUESTIONS);
}

export function defaultQuizQuestionCount(pack: Pick<PackDto, "quizQuestionCount">): number {
  return Math.min(QUIZ_DEFAULT_QUESTIONS, quizQuestionLimit(pack));
}

/** The pack chat/quiz monthly token budget as the QuotaMeter shape. */
export function packTokenQuota(limits: Pick<PackLimitsDto, "chatTokensUsed" | "chatTokensPerMonth">): ChatQuotaDto {
  return { used: limits.chatTokensUsed, limit: limits.chatTokensPerMonth };
}

const QUIZ_STATUS_BADGE: Record<QuizStatus, { label: string; className: string }> = {
  NONE: { label: "Not generated", className: "status-abandoned" },
  GENERATING: { label: "Generating", className: "pack-status-embedding" },
  READY: { label: "Ready", className: "pack-status-ready" },
  FAILED: { label: "Failed", className: "pack-status-failed" },
};

/** Text + `.status-badge` colour modifier for a bank status (unknown values read as NONE). */
export function quizStatusBadge(status: QuizStatus | null | undefined): { label: string; className: string } {
  return (status && QUIZ_STATUS_BADGE[status]) || QUIZ_STATUS_BADGE.NONE;
}

export function quizFailureMessage(pack: Pick<PackDto, "quizErrorMessage">): string {
  const reason = pack.quizErrorMessage?.trim();
  return reason
    ? `Question generation failed: ${reason}`
    : "Question generation failed. Please try again.";
}

export function quizQuotaMessage(now: Date = new Date()): string {
  return `You've used this month's token budget on your plan (shared by chat and quizzes). It resets on ${formatQuotaReset(now)}.`;
}

/** Friendly text for a failed bank generation or pack-session start. Codes the quiz shares with chat
 *  get quiz wording; network / 404 / anything else falls back to the chat mapping. */
export function quizErrorMessage(err: ErrorLike, now: Date = new Date()): string {
  switch (err.code) {
    case "PACK_NOT_READY":
      return "This pack is still being processed — you can quiz yourself on it once it's ready.";
    case "QUIZ_NOT_READY":
      return "This pack's questions aren't ready yet — generate them first.";
    case "QUIZ_ALREADY_GENERATING":
      return "Questions are already being generated for this pack — this page updates when they're ready.";
    case "CHAT_QUOTA_EXCEEDED":
      return quizQuotaMessage(now);
    case "CHAT_UNAVAILABLE":
      return "Quizzes are temporarily unavailable. Please try again later.";
  }
  if (err.status === 400) return err.message || "Those quiz settings weren't accepted — check the options and try again.";
  return chatErrorMessage(err, now);
}
