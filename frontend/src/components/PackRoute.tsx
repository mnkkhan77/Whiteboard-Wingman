import type { ReactNode } from "react";
import { useParams } from "react-router-dom";
import { Navbar } from "./Navbar";
import { StateCard } from "./StateCard";
import { BackToPacksLink } from "./PackStates";

/**
 * Parses `:packId` for a per-pack page. A malformed id gets the not-found card; a valid one is
 * rendered via `render`, which should key its component by the id so switching packs remounts with
 * fresh state (aborting the old page's requests).
 */
export function PackRoute({ render }: { render: (packId: number) => ReactNode }) {
  const { packId } = useParams<{ packId: string }>();
  const id = Number(packId);

  if (!Number.isInteger(id) || id <= 0) {
    return (
      <>
        <Navbar />
        <div className="page">
          <StateCard icon="⚠️">
            <p className="state-message">This study pack doesn't exist or was deleted.</p>
            <BackToPacksLink />
          </StateCard>
        </div>
      </>
    );
  }

  return <>{render(id)}</>;
}
