import type { ReactNode } from "react";
import { ConfirmAction } from "./ConfirmAction";
import { flashcardFailureMessage, flashcardStatusBadge } from "../utils/flashcards";
import type { PackDto } from "../types/api";

interface FlashcardDeckCardProps {
  pack: PackDto;
  /** A generate request is in flight. */
  generating: boolean;
  error: string | null;
  /** Out of monthly tokens — generating would be refused. */
  blocked: boolean;
  onGenerate: () => Promise<void>;
}

const BUDGET_NOTE = "Generating flashcards uses your plan's monthly token budget (shared with chat and quizzes).";

/** The pack's flashcard deck: its status, and generate / regenerate / retry. Mirrors QuizBankCard. */
export function FlashcardDeckCard({ pack, generating, error, blocked, onGenerate }: FlashcardDeckCardProps) {
  const status = pack.flashcardStatus ?? "NONE";
  const badge = flashcardStatusBadge(status);
  const disabled = generating || blocked;

  let description: string;
  let action: ReactNode = null;
  switch (status) {
    case "GENERATING":
      description = "Writing flashcards from your document… This page updates automatically when they're ready.";
      break;
    case "READY": {
      const count = pack.flashcardCount ?? 0;
      description = `${count} card${count === 1 ? "" : "s"} ready, each tied back to its page.`;
      action = (
        <ConfirmAction
          label="Regenerate"
          prompt="Replace the current deck? This loses every card's progress and uses your monthly token budget."
          confirmLabel="Yes, regenerate"
          busyLabel="Starting…"
          confirmClassName="primary"
          disabled={disabled}
          onConfirm={onGenerate}
        />
      );
      break;
    }
    case "FAILED":
      description = flashcardFailureMessage(pack);
      action = (
        <button type="button" className="primary pack-small-button" disabled={disabled} onClick={() => void onGenerate()}>
          {generating ? "Starting…" : "Try again"}
        </button>
      );
      break;
    default:
      description = `No flashcards yet. ${BUDGET_NOTE}`;
      action = (
        <button type="button" className="primary pack-small-button" disabled={disabled} onClick={() => void onGenerate()}>
          {generating ? "Starting…" : "Generate flashcards"}
        </button>
      );
  }

  return (
    <section className="card quiz-bank-card" aria-labelledby="flashcard-deck-heading">
      <div className="pack-row-top">
        <div className="session-row-info">
          <div className="session-row-title">
            <h2 id="flashcard-deck-heading">Flashcard deck</h2>
            <span className={`status-badge ${badge.className}`}>
              {status === "GENERATING" && <span className="spinner spinner-inline" aria-hidden />}
              {badge.label}
            </span>
          </div>
          <p className={status === "FAILED" ? "pack-error" : "hint"} role="status" aria-live="polite">
            {description}
          </p>
        </div>
        {action && <div className="quiz-bank-actions">{action}</div>}
      </div>
      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
