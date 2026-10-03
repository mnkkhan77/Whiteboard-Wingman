// Pure helpers for "flashcards from a pack" (Phase 5) — deck status display and error-code →
// friendly text. Framework-free so they're trivially testable. Mirrors utils/quiz.ts.
import { chatErrorMessage, formatQuotaReset } from "./chat";
import type { FlashcardStatus, PackDto } from "../types/api";

type ErrorLike = { status: number; message: string; code?: string };

const FLASHCARD_STATUS_BADGE: Record<FlashcardStatus, { label: string; className: string }> = {
  NONE: { label: "Not generated", className: "status-abandoned" },
  GENERATING: { label: "Generating", className: "pack-status-embedding" },
  READY: { label: "Ready", className: "pack-status-ready" },
  FAILED: { label: "Failed", className: "pack-status-failed" },
};

/** Text + `.status-badge` colour modifier for a deck status (unknown values read as NONE). */
export function flashcardStatusBadge(status: FlashcardStatus | null | undefined): { label: string; className: string } {
  return (status && FLASHCARD_STATUS_BADGE[status]) || FLASHCARD_STATUS_BADGE.NONE;
}

export function flashcardFailureMessage(pack: Pick<PackDto, "flashcardErrorMessage">): string {
  const reason = pack.flashcardErrorMessage?.trim();
  return reason ? `Flashcard generation failed: ${reason}` : "Flashcard generation failed. Please try again.";
}

export function flashcardQuotaMessage(now: Date = new Date()): string {
  return `You've used this month's token budget on your plan (shared by chat, quizzes and flashcards). It resets on ${formatQuotaReset(now)}.`;
}

/** Friendly text for a failed deck generation / deck read / review. Codes shared with chat get
 *  flashcard wording; network / 404 / anything else falls back to the chat mapping. */
export function flashcardErrorMessage(err: ErrorLike, now: Date = new Date()): string {
  switch (err.code) {
    case "PACK_NOT_READY":
      return "This pack is still being processed — you can make flashcards from it once it's ready.";
    case "FLASHCARDS_NOT_READY":
      return "This pack's flashcards aren't ready yet — generate them first.";
    case "FLASHCARDS_ALREADY_GENERATING":
      return "Flashcards are already being generated for this pack — this page updates when they're ready.";
    case "CHAT_QUOTA_EXCEEDED":
      return flashcardQuotaMessage(now);
    case "CHAT_UNAVAILABLE":
      return "Flashcards are temporarily unavailable. Please try again later.";
  }
  return chatErrorMessage(err, now);
}
