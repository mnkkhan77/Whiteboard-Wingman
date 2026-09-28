import { Link } from "react-router-dom";
import { StateCard } from "./StateCard";
import { isPackInFlight, packErrorMessage } from "../utils/packs";
import type { PackDto } from "../types/api";

export function BackToPacksLink() {
  return (
    <Link to="/packs" className="state-card-link">
      Back to your study packs
    </Link>
  );
}

/** Sign-up CTA for guests, who can't own packs. `message` names what they're missing out on. */
export function PackGuestNotice({ message }: { message: string }) {
  return (
    <div className="empty-state">
      <span className="empty-state-icon">📚</span>
      <p>{message}</p>
      <Link to="/register" className="button primary">
        Sign up
      </Link>
    </div>
  );
}

/** A pack that isn't READY yet (still processing, or FAILED). `activity` e.g. "chat with it". */
export function PackNotReadyCard({ pack, activity }: { pack: PackDto; activity: string }) {
  const processing = isPackInFlight(pack.status);
  return (
    <StateCard icon={processing ? "⏳" : "⚠️"}>
      <p className="state-message">
        {processing ? `This pack is still being processed — you can ${activity} once it's ready.` : packErrorMessage(pack)}
      </p>
      <BackToPacksLink />
    </StateCard>
  );
}
