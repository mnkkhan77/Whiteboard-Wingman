import type { Tier } from "../types/api";

export function TierBadge({ tier }: { tier: Tier }) {
  return <span className={`status-badge tier-${tier.toLowerCase()}`}>{tier}</span>;
}
