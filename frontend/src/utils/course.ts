// Pure helpers for "course from a pack" (Phase 6) — outline status display and error-code →
// friendly text. Framework-free so they're trivially testable. Mirrors utils/quiz.ts / utils/flashcards.ts.
import { chatErrorMessage, formatQuotaReset } from "./chat";
import type { CourseStatus, PackDto } from "../types/api";

type ErrorLike = { status: number; message: string; code?: string };

const COURSE_STATUS_BADGE: Record<CourseStatus, { label: string; className: string }> = {
  NONE: { label: "Not generated", className: "status-abandoned" },
  GENERATING: { label: "Generating", className: "pack-status-embedding" },
  READY: { label: "Ready", className: "pack-status-ready" },
  FAILED: { label: "Failed", className: "pack-status-failed" },
};

/** Text + `.status-badge` colour modifier for an outline status (unknown values read as NONE). */
export function courseStatusBadge(status: CourseStatus | null | undefined): { label: string; className: string } {
  return (status && COURSE_STATUS_BADGE[status]) || COURSE_STATUS_BADGE.NONE;
}

export function courseFailureMessage(pack: Pick<PackDto, "courseErrorMessage">): string {
  const reason = pack.courseErrorMessage?.trim();
  return reason ? `Course generation failed: ${reason}` : "Course generation failed. Please try again.";
}

export function courseQuotaMessage(now: Date = new Date()): string {
  return `You've used this month's token budget on your plan (shared by chat, quizzes and flashcards). It resets on ${formatQuotaReset(now)}.`;
}

/** Friendly text for a failed outline generation / outline read / lesson open. Codes shared with
 *  chat get course wording; network / 404 / anything else falls back to the chat mapping. */
export function courseErrorMessage(err: ErrorLike, now: Date = new Date()): string {
  switch (err.code) {
    case "PACK_NOT_READY":
      return "This pack is still being processed — you can generate a course from it once it's ready.";
    case "COURSE_NOT_READY":
      return "This pack's course isn't ready yet — generate it first.";
    case "COURSE_ALREADY_GENERATING":
      return "A course is already being generated for this pack — this page updates when it's ready.";
    case "CHAT_QUOTA_EXCEEDED":
      return courseQuotaMessage(now);
    case "CHAT_UNAVAILABLE":
      return "Courses are temporarily unavailable. Please try again later.";
    case "LLM_RATE_LIMITED":
      return "The AI provider is busy right now (rate limited). Please try again in a moment.";
    case "LLM_ERROR":
      return "This lesson's content couldn't be written. Please try again.";
  }
  return chatErrorMessage(err, now);
}
