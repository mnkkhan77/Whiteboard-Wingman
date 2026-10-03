import { useState } from "react";
import type { PackFlashcardDto, ReviewQuality } from "../types/api";

interface FlashcardStudySessionProps {
  /** A fixed snapshot of the cards due when the session started — doesn't shrink mid-session even
   *  though each review pushes its card's dueAt into the future. */
  queue: PackFlashcardDto[];
  reviewingId: number | null;
  error: string | null;
  onReview: (cardId: number, quality: ReviewQuality) => Promise<void>;
  onFinish: () => void;
}

const REVIEW_BUTTONS: { quality: ReviewQuality; label: string; hint: string; className: string }[] = [
  { quality: "AGAIN", label: "Again", hint: "Didn't know it", className: "danger" },
  { quality: "HARD", label: "Hard", hint: "Knew it, barely", className: "secondary" },
  { quality: "GOOD", label: "Good", hint: "Knew it", className: "secondary" },
  { quality: "EASY", label: "Easy", hint: "Knew it instantly", className: "primary" },
];

/** One card at a time: flip to reveal the back, then grade yourself with the four SM-2 buttons. */
export function FlashcardStudySession({ queue, reviewingId, error, onReview, onFinish }: FlashcardStudySessionProps) {
  const [index, setIndex] = useState(0);
  const [revealed, setRevealed] = useState(false);

  if (index >= queue.length) {
    return (
      <section className="card flashcard-session-done">
        <h2>Nice work — session complete</h2>
        <p className="hint">You reviewed {queue.length} card{queue.length === 1 ? "" : "s"}.</p>
        <button type="button" className="primary" onClick={onFinish}>
          Back to deck
        </button>
      </section>
    );
  }

  const card = queue[index];
  const busy = reviewingId === card.id;

  const handleAnswer = async (quality: ReviewQuality) => {
    await onReview(card.id, quality);
    setRevealed(false);
    setIndex((i) => i + 1);
  };

  return (
    <section className="card flashcard-session">
      <p className="hint flashcard-progress">
        Card {index + 1} of {queue.length}
      </p>
      <div className={`flashcard ${revealed ? "flashcard-revealed" : ""}`}>
        <p className="flashcard-face flashcard-front">{card.front}</p>
        {revealed && <p className="flashcard-face flashcard-back">{card.back}</p>}
      </div>
      {(card.sourcePage || card.sourceSection) && (
        <p className="hint flashcard-source">
          {card.sourceSection ?? "Source"}
          {card.sourcePage ? ` (p. ${card.sourcePage})` : ""}
        </p>
      )}

      {!revealed ? (
        <button type="button" className="primary start-cta" onClick={() => setRevealed(true)}>
          Show answer
        </button>
      ) : (
        <div className="flashcard-review-buttons">
          {REVIEW_BUTTONS.map((b) => (
            <button
              key={b.quality}
              type="button"
              className={`${b.className} pack-small-button`}
              disabled={busy}
              title={b.hint}
              onClick={() => void handleAnswer(b.quality)}
            >
              {busy ? "…" : b.label}
            </button>
          ))}
        </div>
      )}

      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
