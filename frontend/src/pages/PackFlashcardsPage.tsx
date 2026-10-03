import { useState, type ReactNode } from "react";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { QuotaMeter } from "../components/QuotaMeter";
import { StateCard } from "../components/StateCard";
import { BackToPacksLink, PackGuestNotice, PackNotReadyCard } from "../components/PackStates";
import { PackPageHeader } from "../components/PackPageHeader";
import { PackRoute } from "../components/PackRoute";
import { FlashcardDeckCard } from "../components/FlashcardDeckCard";
import { FlashcardStudySession } from "../components/FlashcardStudySession";
import { FlashcardBrowseList } from "../components/FlashcardBrowseList";
import { usePackFlashcards } from "../hooks/usePackFlashcards";
import { isQuotaExhausted } from "../utils/chat";
import { flashcardQuotaMessage } from "../utils/flashcards";
import type { PackFlashcardDto } from "../types/api";

function PackFlashcards({ packId }: { packId: number }) {
  const { token, guest } = useAuth();
  // Guests can't own packs — skip the requests and show the sign-up CTA instead.
  const {
    pack,
    quota,
    loadError,
    reload,
    generating,
    generateError,
    generate,
    cards,
    dueCount,
    deckError,
    reloadDeck,
    review,
    reviewingId,
    reviewError,
  } = usePackFlashcards(guest ? null : token, packId);

  const [studyQueue, setStudyQueue] = useState<PackFlashcardDto[] | null>(null);

  const exhausted = isQuotaExhausted(quota);
  let body: ReactNode;
  if (guest) {
    body = (
      <PackGuestNotice message="Study packs are for signed-up users — create a free account to upload documents and make flashcards from them." />
    );
  } else if (loadError) {
    body = (
      <StateCard icon="⚠️">
        <p className="state-message">{loadError.message}</p>
        {loadError.notFound ? (
          <BackToPacksLink />
        ) : (
          <button type="button" className="secondary" onClick={reload}>
            Try again
          </button>
        )}
      </StateCard>
    );
  } else if (!pack) {
    body = (
      <StateCard>
        <p className="state-message">Loading the flashcards…</p>
      </StateCard>
    );
  } else if (pack.status !== "READY") {
    body = <PackNotReadyCard pack={pack} activity="make flashcards from it" />;
  } else {
    body = (
      <>
        {exhausted && <p className="keyless-warning">{flashcardQuotaMessage()}</p>}
        <FlashcardDeckCard pack={pack} generating={generating} error={generateError} blocked={exhausted} onGenerate={generate} />
        {pack.flashcardStatus === "READY" &&
          (studyQueue ? (
            <FlashcardStudySession
              queue={studyQueue}
              reviewingId={reviewingId}
              error={reviewError}
              onReview={review}
              onFinish={() => setStudyQueue(null)}
            />
          ) : deckError ? (
            <StateCard icon="⚠️">
              <p className="state-message">{deckError}</p>
              <button type="button" className="secondary" onClick={reloadDeck}>
                Try again
              </button>
            </StateCard>
          ) : cards === null ? (
            <StateCard>
              <p className="state-message">Loading your deck…</p>
            </StateCard>
          ) : (
            <>
              <section className="card">
                <h2>Study</h2>
                {dueCount > 0 ? (
                  <>
                    <p className="hint">
                      {dueCount} card{dueCount === 1 ? "" : "s"} due for review right now.
                    </p>
                    <button
                      type="button"
                      className="primary start-cta"
                      onClick={() => setStudyQueue(cards.filter((c) => new Date(c.dueAt).getTime() <= Date.now()))}
                    >
                      Study now →
                    </button>
                  </>
                ) : (
                  <p className="hint">All caught up — no cards due right now. Check back later.</p>
                )}
              </section>
              <FlashcardBrowseList cards={cards} />
            </>
          ))}
      </>
    );
  }

  return (
    <>
      <Navbar />
      <div className="page">
        <PackPageHeader eyebrow="Flashcards" pack={pack}>
          {pack?.status === "READY" && quota && <QuotaMeter quota={quota} label="Tokens this month (chat + quizzes + flashcards)" />}
        </PackPageHeader>
        {body}
      </div>
      <Footer />
    </>
  );
}

export default function PackFlashcardsPage() {
  // Keyed by pack so navigating between packs remounts with fresh state (and stops the old poll).
  return <PackRoute render={(id) => <PackFlashcards key={id} packId={id} />} />;
}
