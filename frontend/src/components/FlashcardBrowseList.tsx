import type { PackFlashcardDto } from "../types/api";

function isDue(card: PackFlashcardDto, now: Date): boolean {
  return new Date(card.dueAt).getTime() <= now.getTime();
}

/** Every card in the deck, front + back + its SM-2 schedule, newest-written first is not assumed —
 *  shown in the order the deck returns them (creation order). Read-only: reviewing only happens in
 *  a study session. */
export function FlashcardBrowseList({ cards }: { cards: PackFlashcardDto[] }) {
  const now = new Date();
  return (
    <section className="card flashcard-browse">
      <h2>All cards ({cards.length})</h2>
      <ul className="flashcard-browse-list">
        {cards.map((card) => (
          <li key={card.id} className="flashcard-browse-item">
            <div className="flashcard-browse-text">
              <strong>{card.front}</strong>
              <span>{card.back}</span>
            </div>
            <span className={`status-badge ${isDue(card, now) ? "pack-status-embedding" : "status-abandoned"}`}>
              {isDue(card, now) ? "Due now" : `Due ${new Date(card.dueAt).toLocaleDateString()}`}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
