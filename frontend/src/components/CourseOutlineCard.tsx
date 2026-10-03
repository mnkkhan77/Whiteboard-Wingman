import type { ReactNode } from "react";
import { ConfirmAction } from "./ConfirmAction";
import { courseFailureMessage, courseStatusBadge } from "../utils/course";
import type { PackDto } from "../types/api";

interface CourseOutlineCardProps {
  pack: PackDto;
  /** A generate request is in flight. */
  generating: boolean;
  error: string | null;
  /** Out of monthly tokens — generating would be refused. */
  blocked: boolean;
  onGenerate: () => Promise<void>;
}

const BUDGET_NOTE = "Generating a course uses your plan's monthly token budget (shared with chat, quizzes and flashcards).";

/** The pack's course outline: its status, and generate / regenerate / retry. Mirrors QuizBankCard / FlashcardDeckCard. */
export function CourseOutlineCard({ pack, generating, error, blocked, onGenerate }: CourseOutlineCardProps) {
  const status = pack.courseStatus ?? "NONE";
  const badge = courseStatusBadge(status);
  const disabled = generating || blocked;

  let description: string;
  let action: ReactNode = null;
  switch (status) {
    case "GENERATING":
      description = "Designing a course from your document… This page updates automatically when it's ready.";
      break;
    case "READY": {
      const count = pack.courseLessonCount ?? 0;
      description = `${count} lesson${count === 1 ? "" : "s"} ready, each tied back to its page.`;
      action = (
        <ConfirmAction
          label="Regenerate"
          prompt="Replace the current course? This loses every lesson's written content and completion progress, and uses your monthly token budget."
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
      description = courseFailureMessage(pack);
      action = (
        <button type="button" className="primary pack-small-button" disabled={disabled} onClick={() => void onGenerate()}>
          {generating ? "Starting…" : "Try again"}
        </button>
      );
      break;
    default:
      description = `No course yet. ${BUDGET_NOTE}`;
      action = (
        <button type="button" className="primary pack-small-button" disabled={disabled} onClick={() => void onGenerate()}>
          {generating ? "Starting…" : "Generate course"}
        </button>
      );
  }

  return (
    <section className="card quiz-bank-card" aria-labelledby="course-outline-heading">
      <div className="pack-row-top">
        <div className="session-row-info">
          <div className="session-row-title">
            <h2 id="course-outline-heading">Course outline</h2>
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
