import type { ReactNode } from "react";
import { ConfirmAction } from "./ConfirmAction";
import { quizFailureMessage, quizStatusBadge } from "../utils/quiz";
import type { PackDto } from "../types/api";

interface QuizBankCardProps {
  pack: PackDto;
  /** A generate request is in flight. */
  generating: boolean;
  error: string | null;
  /** Out of monthly tokens — generating would be refused. */
  blocked: boolean;
  onGenerate: () => Promise<void>;
}

const BUDGET_NOTE = "Generating questions uses your plan's monthly token budget (shared with chat).";

/** The pack's question bank: its status, and generate / regenerate / retry. */
export function QuizBankCard({ pack, generating, error, blocked, onGenerate }: QuizBankCardProps) {
  const status = pack.quizStatus ?? "NONE";
  const badge = quizStatusBadge(status);
  const disabled = generating || blocked;

  let description: string;
  let action: ReactNode = null;
  switch (status) {
    case "GENERATING":
      description = "Writing questions from your document… This page updates automatically when they're ready.";
      break;
    case "READY": {
      const count = pack.quizQuestionCount ?? 0;
      description = `${count} question${count === 1 ? "" : "s"} ready — a mix of verbal and multiple choice, each tied back to its page.`;
      action = (
        <ConfirmAction
          label="Regenerate"
          prompt="Replace the current questions? This uses your monthly token budget."
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
      description = quizFailureMessage(pack);
      action = (
        <button type="button" className="primary pack-small-button" disabled={disabled} onClick={() => void onGenerate()}>
          {generating ? "Starting…" : "Try again"}
        </button>
      );
      break;
    default:
      description = `No questions yet. ${BUDGET_NOTE}`;
      action = (
        <button type="button" className="primary pack-small-button" disabled={disabled} onClick={() => void onGenerate()}>
          {generating ? "Starting…" : "Generate questions"}
        </button>
      );
  }

  return (
    <section className="card quiz-bank-card" aria-labelledby="quiz-bank-heading">
      <div className="pack-row-top">
        <div className="session-row-info">
          <div className="session-row-title">
            <h2 id="quiz-bank-heading">Question bank</h2>
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
